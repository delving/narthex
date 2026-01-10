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

define(["angular"], function (angular) {
    "use strict";

    /**
     * Controller for the Index Stats page
     * Compares dataset record counts between Narthex and Hub3 search index
     */
    var IndexStatsCtrl = function ($scope, $http, datasetListService) {
        // Tab state
        $scope.activeTab = 'correct';
        $scope.loading = true;
        $scope.error = null;
        $scope.searchQuery = '';

        // Stats data
        $scope.stats = {
            totalIndexed: 0,
            totalDatasets: 0,
            correct: [],
            notIndexed: [],
            notProcessed: [],
            wrongCount: [],
            disabled: [],
            deleted: []
        };

        // Sorting
        $scope.sortField = 'spec';
        $scope.sortReverse = false;

        // Track pending operations
        $scope.pendingOps = {};

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
         * Clear search query
         */
        $scope.clearSearch = function () {
            $scope.searchQuery = '';
        };

        /**
         * Load index statistics from the API (with trends data)
         */
        $scope.loadStats = function () {
            $scope.loading = true;
            $scope.error = null;

            $http.get('/narthex/app/index-stats-with-trends').then(
                function (response) {
                    $scope.stats = response.data;
                    $scope.loading = false;
                },
                function (error) {
                    console.error('Error loading index stats:', error);
                    $scope.error = 'Failed to load index statistics';
                    $scope.loading = false;
                }
            );
        };

        /**
         * Set the active tab
         */
        $scope.setTab = function (tab) {
            $scope.activeTab = tab;
        };

        /**
         * Get raw datasets for a tab (before search filter)
         */
        function getRawDatasets(tab) {
            switch (tab) {
                case 'correct':
                    return $scope.stats.correct || [];
                case 'notIndexed':
                    return $scope.stats.notIndexed || [];
                case 'notProcessed':
                    return $scope.stats.notProcessed || [];
                case 'wrongCount':
                    return $scope.stats.wrongCount || [];
                case 'disabled':
                    return $scope.stats.disabled || [];
                case 'deleted':
                    return $scope.stats.deleted || [];
                default:
                    return [];
            }
        }

        /**
         * Get the datasets for the active tab (with search filter applied)
         */
        $scope.getActiveDatasets = function () {
            return filterBySearch(getRawDatasets($scope.activeTab));
        };

        /**
         * Get count for a tab (with search filter applied)
         */
        $scope.getTabCount = function (tab) {
            return filterBySearch(getRawDatasets(tab)).length;
        };

        /**
         * Sort by a field
         */
        $scope.sortBy = function (field) {
            if ($scope.sortField === field) {
                $scope.sortReverse = !$scope.sortReverse;
            } else {
                $scope.sortField = field;
                $scope.sortReverse = false;
            }
        };

        /**
         * Get sort icon class
         */
        $scope.getSortIcon = function (field) {
            if ($scope.sortField !== field) {
                return 'fa-sort text-muted';
            }
            return $scope.sortReverse ? 'fa-sort-desc' : 'fa-sort-asc';
        };

        /**
         * Format number with thousand separators
         */
        $scope.formatNumber = function (num) {
            if (num === null || num === undefined) {
                return '-';
            }
            return num.toLocaleString();
        };

        /**
         * Calculate difference between valid and indexed counts
         */
        $scope.getDifference = function (ds) {
            var valid = ds.processedValid || 0;
            var indexed = ds.indexCount || 0;
            return indexed - valid;
        };

        /**
         * Get CSS class for difference
         */
        $scope.getDifferenceClass = function (ds) {
            var diff = $scope.getDifference(ds);
            if (diff === 0) {
                return 'text-success';
            } else if (diff > 0) {
                return 'text-warning';
            } else {
                return 'text-danger';
            }
        };

        /**
         * Format a trend delta value with +/- prefix
         */
        $scope.formatDelta = function (value) {
            if (value === null || value === undefined || value === 0) {
                return '-';
            }
            var sign = value > 0 ? '+' : '';
            return sign + value.toLocaleString();
        };

        /**
         * Get CSS class for trend delta
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
         * Trigger a save operation for a dataset
         */
        $scope.triggerSave = function (spec) {
            if ($scope.pendingOps[spec]) {
                return; // Already pending
            }
            $scope.pendingOps[spec] = true;
            datasetListService.command(spec, "start saving").then(
                function () {
                    console.log("Save triggered for: " + spec);
                    // Refresh stats after a short delay to show updated state
                    setTimeout(function () {
                        $scope.loadStats();
                        delete $scope.pendingOps[spec];
                    }, 2000);
                },
                function (error) {
                    console.error("Error triggering save for " + spec + ":", error);
                    delete $scope.pendingOps[spec];
                }
            );
        };

        /**
         * Check if an operation is pending for a dataset
         */
        $scope.isPending = function (spec) {
            return $scope.pendingOps[spec] === true;
        };

        // Load stats on controller init
        $scope.loadStats();
    };

    IndexStatsCtrl.$inject = ['$scope', '$http', 'datasetListService'];

    return {
        IndexStatsCtrl: IndexStatsCtrl
    };
});
