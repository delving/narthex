//===========================================================================
//    Copyright 2014 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================

define(["angular"], function (angular) {
    "use strict";

    var StatsCtrl = function ($scope, $http) {
        $scope.grouping = 'dataset';
        $scope.loading = true;
        $scope.completionDetails = [];
        $scope.groupedByDataset = [];
        $scope.groupedByHour = [];
        $scope.expandedDataset = null;
        $scope.expandedHour = null;
        $scope.uniqueDatasets = 0;
        $scope.manualCount = 0;
        $scope.automaticCount = 0;

        // Sort state for dataset view
        $scope.datasetSortField = 'count';
        $scope.datasetSortReverse = true;

        // Format relative time
        $scope.formatRelativeTime = function(timestamp) {
            var now = Date.now();
            var diff = now - timestamp;
            var minutes = Math.floor(diff / (1000 * 60));
            var hours = Math.floor(diff / (1000 * 60 * 60));

            if (minutes < 1) return 'just now';
            if (minutes < 60) return minutes + ' minute' + (minutes === 1 ? '' : 's') + ' ago';
            if (hours === 1) return '1 hour ago';
            if (hours < 24) return hours + ' hours ago';
            return Math.floor(hours / 24) + ' day(s) ago';
        };

        // Format duration
        $scope.formatDuration = function(seconds) {
            if (seconds === null || seconds === undefined) return '-';
            if (seconds < 60) return Math.round(seconds) + 's';
            var minutes = Math.floor(seconds / 60);
            var secs = Math.round(seconds % 60);
            return minutes + 'm ' + secs + 's';
        };

        // Load stats data
        function loadStats() {
            $scope.loading = true;
            $http.get('/narthex/app/active-datasets').then(function(response) {
                $scope.loading = false;
                $scope.completionDetails = response.data.completionDetails || [];

                // Calculate summary counts
                var seen = {};
                $scope.manualCount = 0;
                $scope.automaticCount = 0;

                $scope.completionDetails.forEach(function(op) {
                    seen[op.spec] = true;
                    if (op.trigger === 'manual') {
                        $scope.manualCount++;
                    } else {
                        $scope.automaticCount++;
                    }
                });
                $scope.uniqueDatasets = Object.keys(seen).length;

                updateGroupings();
            }, function(error) {
                $scope.loading = false;
                console.error('Failed to load stats:', error);
            });
        }

        function updateGroupings() {
            $scope.groupedByDataset = groupByDataset($scope.completionDetails);
            $scope.groupedByHour = groupByRelativeHour($scope.completionDetails);
        }

        function groupByDataset(details) {
            var grouped = {};
            details.forEach(function(op) {
                if (!grouped[op.spec]) {
                    grouped[op.spec] = {
                        spec: op.spec,
                        count: 0,
                        manual: 0,
                        automatic: 0,
                        operations: [],
                        lastActivity: 0
                    };
                }
                grouped[op.spec].count++;
                if (op.trigger === 'manual') {
                    grouped[op.spec].manual++;
                } else {
                    grouped[op.spec].automatic++;
                }
                grouped[op.spec].operations.push(op);
                if (op.completedAt > grouped[op.spec].lastActivity) {
                    grouped[op.spec].lastActivity = op.completedAt;
                }
            });

            var result = [];
            for (var key in grouped) {
                if (grouped.hasOwnProperty(key)) {
                    result.push(grouped[key]);
                }
            }

            // Sort by count descending by default
            return result.sort(function(a, b) { return b.count - a.count; });
        }

        function groupByRelativeHour(details) {
            var now = Date.now();
            var grouped = [];

            for (var h = 0; h < 24; h++) {
                grouped.push({
                    hoursAgo: h,
                    relativeLabel: h === 0 ? 'Last hour' : h + ' hour' + (h === 1 ? '' : 's') + ' ago',
                    count: 0,
                    datasetCount: 0,
                    datasets: {},
                    operations: []
                });
            }

            details.forEach(function(op) {
                var hoursAgo = Math.floor((now - op.completedAt) / (1000 * 60 * 60));
                if (hoursAgo >= 0 && hoursAgo < 24) {
                    grouped[hoursAgo].count++;
                    grouped[hoursAgo].datasets[op.spec] = true;
                    grouped[hoursAgo].operations.push(op);
                }
            });

            grouped.forEach(function(g) {
                g.datasetCount = Object.keys(g.datasets).length;
            });

            return grouped;
        }

        $scope.setGrouping = function(g) {
            $scope.grouping = g;
            $scope.expandedDataset = null;
            $scope.expandedHour = null;
        };

        $scope.expandDataset = function(item) {
            $scope.expandedDataset = $scope.expandedDataset === item ? null : item;
        };

        $scope.expandHour = function(item) {
            $scope.expandedHour = $scope.expandedHour === item ? null : item;
        };

        $scope.sortByDataset = function(field) {
            if ($scope.datasetSortField === field) {
                $scope.datasetSortReverse = !$scope.datasetSortReverse;
            } else {
                $scope.datasetSortField = field;
                $scope.datasetSortReverse = field !== 'spec'; // Alpha sort ascending, numbers descending
            }

            $scope.groupedByDataset.sort(function(a, b) {
                var aVal = a[field];
                var bVal = b[field];
                var cmp = 0;
                if (typeof aVal === 'string') {
                    cmp = aVal.localeCompare(bVal);
                } else {
                    cmp = aVal - bVal;
                }
                return $scope.datasetSortReverse ? -cmp : cmp;
            });
        };

        $scope.refreshStats = function() {
            loadStats();
        };

        // Initial load
        loadStats();
    };

    StatsCtrl.$inject = ['$scope', '$http'];

    return {
        StatsCtrl: StatsCtrl
    };
});
