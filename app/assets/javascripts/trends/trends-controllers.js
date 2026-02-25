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

define(["angular", "Chart"], function (angular, Chart) {
    "use strict";

    var TrendsCtrl = function ($scope, $http, $timeout) {
        $scope.loading = true;
        $scope.error = null;
        $scope.trends = null;
        $scope.activeTab = 'growing';
        $scope.timeWindow = '24h';
        $scope.searchQuery = '';

        // Chart state
        $scope.expandedSpec = null;
        $scope.chartLoading = false;
        $scope.chartData = null;
        var currentChart = null;

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

            var all = [].concat(
                $scope.trends.growing || [],
                $scope.trends.shrinking || [],
                $scope.trends.stable || []
            );

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
            // If chart is open, reload it with new window
            if ($scope.expandedSpec) {
                loadChartData($scope.expandedSpec);
            }
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
         * Toggle chart for a dataset
         */
        $scope.toggleChart = function (spec) {
            if ($scope.expandedSpec === spec) {
                $scope.expandedSpec = null;
                destroyChart();
                return;
            }
            $scope.expandedSpec = spec;
            loadChartData(spec);
        };

        /**
         * Load chart data for a specific dataset
         */
        function loadChartData(spec) {
            $scope.chartLoading = true;
            $scope.chartData = null;

            $http.get('/narthex/app/trends/' + spec).then(
                function (response) {
                    $scope.chartData = response.data;
                    $scope.chartLoading = false;
                    // Render chart on next digest cycle
                    $timeout(function () {
                        renderChart(response.data);
                    }, 50);
                },
                function (error) {
                    console.error('Error loading chart data:', error);
                    $scope.chartLoading = false;
                }
            );
        }

        /**
         * Destroy current chart instance
         */
        function destroyChart() {
            if (currentChart) {
                currentChart.destroy();
                currentChart = null;
            }
        }

        /**
         * Format timestamp for display
         */
        function formatTimestamp(ts) {
            var d = new Date(ts);
            return d.toLocaleDateString('nl-NL', { day: '2-digit', month: '2-digit' });
        }

        function formatTimestampFull(ts) {
            var d = new Date(ts);
            return d.toLocaleDateString('nl-NL', { day: '2-digit', month: '2-digit' }) +
                ' ' + d.toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit' });
        }

        /**
         * Render the chart based on current time window and data
         */
        function renderChart(data) {
            destroyChart();

            var canvas = document.getElementById('trend-chart');
            if (!canvas) return;

            var ctx = canvas.getContext('2d');
            var labels, sourceData, indexedData, validData;

            if ($scope.timeWindow === '24h' && data.recentEvents && data.recentEvents.length > 0) {
                // 24h view: show event-level snapshots
                labels = data.recentEvents.map(function (s) { return formatTimestampFull(s.timestamp); });
                sourceData = data.recentEvents.map(function (s) { return s.sourceRecords; });
                indexedData = data.recentEvents.map(function (s) { return s.indexedRecords; });
                validData = data.recentEvents.map(function (s) { return s.validRecords; });
            } else if (data.dailySummaries && data.dailySummaries.length > 0) {
                // 7d/30d view: show daily summaries
                var summaries = data.dailySummaries;
                if ($scope.timeWindow === '7d') {
                    summaries = summaries.slice(-7);
                } else if ($scope.timeWindow === '24h') {
                    // Fallback if no recent events: show last 3 days
                    summaries = summaries.slice(-3);
                }
                // For 30d, show all (already limited to 30 by backend)

                labels = summaries.map(function (s) { return s.date.substring(5); }); // "MM-DD"
                sourceData = summaries.map(function (s) { return s.endOfDay.sourceRecords; });
                indexedData = summaries.map(function (s) { return s.endOfDay.indexedRecords; });
                validData = summaries.map(function (s) { return s.endOfDay.validRecords; });
            } else if (data.history && data.history.length > 0) {
                // Legacy fallback: use history snapshots
                var history = data.history;
                if ($scope.timeWindow === '7d') {
                    history = history.slice(-7);
                } else if ($scope.timeWindow === '24h') {
                    history = history.slice(-3);
                }
                labels = history.map(function (s) { return formatTimestamp(s.timestamp); });
                sourceData = history.map(function (s) { return s.sourceRecords; });
                indexedData = history.map(function (s) { return s.indexedRecords; });
                validData = history.map(function (s) { return s.validRecords; });
            } else {
                return; // No data to chart
            }

            currentChart = new Chart(ctx, {
                type: 'line',
                data: {
                    labels: labels,
                    datasets: [
                        {
                            label: 'Source Records',
                            data: sourceData,
                            borderColor: '#337ab7',
                            backgroundColor: 'rgba(51, 122, 183, 0.1)',
                            borderWidth: 2,
                            pointRadius: 3,
                            pointHoverRadius: 5,
                            fill: false
                        },
                        {
                            label: 'Valid Records',
                            data: validData,
                            borderColor: '#5cb85c',
                            backgroundColor: 'rgba(92, 184, 92, 0.1)',
                            borderWidth: 2,
                            pointRadius: 3,
                            pointHoverRadius: 5,
                            fill: false
                        },
                        {
                            label: 'Indexed Records',
                            data: indexedData,
                            borderColor: '#f0ad4e',
                            backgroundColor: 'rgba(240, 173, 78, 0.1)',
                            borderWidth: 2,
                            pointRadius: 3,
                            pointHoverRadius: 5,
                            fill: false
                        }
                    ]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    legend: {
                        position: 'top',
                        labels: { boxWidth: 12, padding: 15 }
                    },
                    tooltips: {
                        mode: 'index',
                        intersect: false,
                        callbacks: {
                            label: function (item, data) {
                                var label = data.datasets[item.datasetIndex].label || '';
                                return label + ': ' + item.yLabel.toLocaleString();
                            }
                        }
                    },
                    scales: {
                        xAxes: [{
                            gridLines: { display: false },
                            ticks: { maxRotation: 45, autoSkip: true, maxTicksLimit: 15 }
                        }],
                        yAxes: [{
                            ticks: {
                                beginAtZero: false,
                                callback: function (value) { return value.toLocaleString(); }
                            },
                            gridLines: { color: 'rgba(0,0,0,0.05)' }
                        }]
                    }
                }
            });
        }

        /**
         * Trigger manual snapshot
         */
        $scope.triggerSnapshot = function () {
            $scope.snapshotPending = true;

            $http.post('/narthex/app/trends/snapshot').then(
                function (response) {
                    $scope.snapshotPending = false;
                    $scope.snapshotResult = response.data;
                    $scope.loadTrends();
                },
                function (error) {
                    console.error('Error triggering snapshot:', error);
                    $scope.snapshotPending = false;
                    $scope.snapshotError = 'Failed to trigger snapshot';
                }
            );
        };

        // Clean up chart on scope destroy
        $scope.$on('$destroy', function () {
            destroyChart();
        });

        // Initial load
        $scope.loadTrends();
    };

    TrendsCtrl.$inject = ['$scope', '$http', '$timeout'];

    return {
        TrendsCtrl: TrendsCtrl
    };
});
