//===========================================================================
//    Copyright 2026 Delving B.V.
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

define(["angular"], function () {
    "use strict";

    var TrendsCtrl = function ($scope, $http) {
        $scope.loading = true;
        $scope.error = null;
        $scope.trends = null;
        $scope.activeTab = 'growing';
        $scope.timeWindow = '24h';
        $scope.searchQuery = '';

        // Cached categorization based on time window
        $scope.categorized = {
            growing: [],
            shrinking: [],
            stable: []
        };
        $scope.currentNetDelta = { source: 0, valid: 0, indexed: 0 };

        /**
         * Get the delta key for current time window
         */
        function getDeltaKey() {
            return 'delta' + $scope.timeWindow;
        }

        /**
         * Get delta for a dataset for current time window
         */
        function getDeltaForDataset(ds) {
            var key = getDeltaKey();
            return ds[key] || { source: 0, valid: 0, indexed: 0 };
        }

        /**
         * Recategorize datasets based on selected time window
         */
        function recategorizeDatasets() {
            if (!$scope.trends) return;

            // Combine all datasets from all categories
            var all = [].concat(
                $scope.trends.growing || [],
                $scope.trends.shrinking || [],
                $scope.trends.stable || []
            );

            // Categorize based on selected time window
            var growing = [];
            var shrinking = [];
            var stable = [];
            var netSource = 0;
            var netValid = 0;
            var netIndexed = 0;

            all.forEach(function(ds) {
                var delta = getDeltaForDataset(ds);
                netSource += delta.source || 0;
                netValid += delta.valid || 0;
                netIndexed += delta.indexed || 0;

                if ((delta.source > 0) || (delta.indexed > 0)) {
                    growing.push(ds);
                } else if ((delta.source < 0) || (delta.indexed < 0)) {
                    shrinking.push(ds);
                } else {
                    stable.push(ds);
                }
            });

            // Sort by magnitude of change
            growing.sort(function(a, b) {
                var deltaA = getDeltaForDataset(a);
                var deltaB = getDeltaForDataset(b);
                return (deltaB.source + deltaB.indexed) - (deltaA.source + deltaA.indexed);
            });
            shrinking.sort(function(a, b) {
                var deltaA = getDeltaForDataset(a);
                var deltaB = getDeltaForDataset(b);
                return (deltaA.source + deltaA.indexed) - (deltaB.source + deltaB.indexed);
            });
            stable.sort(function(a, b) {
                return a.spec.localeCompare(b.spec);
            });

            $scope.categorized = {
                growing: growing,
                shrinking: shrinking,
                stable: stable
            };
            $scope.currentNetDelta = { source: netSource, valid: netValid, indexed: netIndexed };
        }

        /**
         * Load organization trends from the API
         */
        $scope.loadTrends = function () {
            $scope.loading = true;
            $scope.error = null;

            $http.get('/narthex/app/trends').then(
                function (response) {
                    $scope.trends = response.data;
                    recategorizeDatasets();
                    $scope.loading = false;
                },
                function (error) {
                    console.error('Error loading trends:', error);
                    $scope.error = 'Failed to load trends data';
                    $scope.loading = false;
                }
            );
        };

        /**
         * Set the time window and recategorize
         */
        $scope.setTimeWindow = function (window) {
            $scope.timeWindow = window;
            recategorizeDatasets();
        };

        /**
         * Set the active tab
         */
        $scope.setTab = function (tab) {
            $scope.activeTab = tab;
        };

        /**
         * Filter datasets by search query
         */
        function filterBySearch(datasets) {
            if (!$scope.searchQuery || $scope.searchQuery.trim() === '') {
                return datasets;
            }
            var query = $scope.searchQuery.toLowerCase().trim();
            return datasets.filter(function(ds) {
                return ds.spec.toLowerCase().indexOf(query) !== -1;
            });
        }

        /**
         * Get datasets for current tab (using recategorized data, filtered by search)
         */
        $scope.getActiveDatasets = function () {
            var datasets = $scope.categorized[$scope.activeTab] || [];
            return filterBySearch(datasets);
        };

        /**
         * Get tab count (using recategorized data, filtered by search)
         */
        $scope.getTabCount = function (tab) {
            var datasets = $scope.categorized[tab] || [];
            return filterBySearch(datasets).length;
        };

        /**
         * Clear search query
         */
        $scope.clearSearch = function () {
            $scope.searchQuery = '';
        };

        /**
         * Get human-readable label for current time window
         */
        $scope.getTimeWindowLabel = function () {
            switch ($scope.timeWindow) {
                case '24h': return '24 hours';
                case '7d': return '7 days';
                case '30d': return '30 days';
                default: return $scope.timeWindow;
            }
        };

        /**
         * Format a number with thousands separator
         */
        $scope.formatNumber = function (num) {
            if (num === null || num === undefined) return '-';
            return num.toLocaleString();
        };

        /**
         * Format a delta value with +/- prefix
         */
        $scope.formatDelta = function (value) {
            if (value === null || value === undefined || value === 0) {
                return '-';
            }
            var sign = value > 0 ? '+' : '';
            return sign + value.toLocaleString();
        };

        /**
         * Get CSS class for delta
         */
        $scope.getDeltaClass = function (value) {
            if (!value || value === 0) {
                return 'text-muted';
            } else if (value > 0) {
                return 'text-success';
            } else {
                return 'text-danger';
            }
        };

        /**
         * Get delta for current time window
         */
        $scope.getDelta = function (dataset, field) {
            if (!dataset) return 0;

            var deltaKey = 'delta' + $scope.timeWindow;
            var delta = dataset[deltaKey];
            if (!delta) return 0;

            return delta[field] || 0;
        };

        /**
         * Trigger manual snapshot
         */
        $scope.triggerSnapshot = function () {
            $scope.snapshotPending = true;

            $http.post('/narthex/app/trends/snapshot').then(
                function (response) {
                    $scope.snapshotPending = false;
                    $scope.snapshotResult = response.data;
                    // Reload trends after snapshot
                    $scope.loadTrends();
                },
                function (error) {
                    console.error('Error triggering snapshot:', error);
                    $scope.snapshotPending = false;
                    $scope.snapshotError = 'Failed to trigger snapshot';
                }
            );
        };

        // Initial load
        $scope.loadTrends();
    };

    TrendsCtrl.$inject = ['$scope', '$http'];

    return {
        TrendsCtrl: TrendsCtrl
    };
});
