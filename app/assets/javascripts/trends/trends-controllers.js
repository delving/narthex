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

        /**
         * Load organization trends from the API
         */
        $scope.loadTrends = function () {
            $scope.loading = true;
            $scope.error = null;

            $http.get('/narthex/app/trends').then(
                function (response) {
                    $scope.trends = response.data;
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
         * Set the active tab
         */
        $scope.setTab = function (tab) {
            $scope.activeTab = tab;
        };

        /**
         * Get datasets for current tab
         */
        $scope.getActiveDatasets = function () {
            if (!$scope.trends) return [];

            switch ($scope.activeTab) {
                case 'growing':
                    return $scope.trends.growing || [];
                case 'shrinking':
                    return $scope.trends.shrinking || [];
                case 'stable':
                    return $scope.trends.stable || [];
                default:
                    return [];
            }
        };

        /**
         * Get tab count
         */
        $scope.getTabCount = function (tab) {
            if (!$scope.trends) return 0;

            switch (tab) {
                case 'growing':
                    return ($scope.trends.growing || []).length;
                case 'shrinking':
                    return ($scope.trends.shrinking || []).length;
                case 'stable':
                    return ($scope.trends.stable || []).length;
                default:
                    return 0;
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
