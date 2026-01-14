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


String.prototype.endsWith = function (suffix) {
    return this.indexOf(suffix, this.length - suffix.length) !== -1;
};

define(["angular"], function () {
    "use strict";


    var progressStates = {
        'state-harvesting': "Harvesting",
        'state-collecting': "Collecting",
        'state-adopting': "Adopting",
        'state-generating': "Generating",
        'state-splitting': "Splitting",
        'state-collating': "Collating",
        'state-categorizing': "Categorizing",
        'state-processing': "Processing",
        'state-saving': "Saving",
        'state-skosifying': "Skosifying",
        'state-error': "Error"
    };

    var DatasetListCtrl = function ($rootScope, $scope, datasetListService, $location, pageScroll, modalAlert, $timeout, $routeParams) {

        $scope.apiPrefix = "/narthex/api/"
        $scope.enableIncrementalHarvest = $rootScope.enableIncrementalHarvest;
        $scope.uploading = false;
        $scope.datasets = [];
        $scope.percent = null;
        $scope.dropSupported = false;
        $scope.newFileOpen = false;
        $scope.newDataset = {};
        $scope.specOrNameFilter = "";
        $scope.stateFilter = "";
        $scope.socketSubscribers = {};

        // WebSocket connection state
        var socket;
        var reconnectAttempts = 0;
        var maxReconnectAttempts = 10;
        var reconnectDelay = 1000;
        var heartbeatInterval;
        var isReconnecting = false;
        var intentionallyClosed = false;

        function createWebSocket(path) {
            var protocolPrefix = ($location.$$protocol === 'https') ? 'wss:' : 'ws:';
            return new WebSocket(protocolPrefix + '//' + location.host + path);
        }

        function startHeartbeat() {
            stopHeartbeat();
            heartbeatInterval = setInterval(function() {
                if (socket && socket.readyState === WebSocket.OPEN) {
                    socket.send("ping");
                }
            }, 30000);
        }

        function stopHeartbeat() {
            if (heartbeatInterval) {
                clearInterval(heartbeatInterval);
                heartbeatInterval = null;
            }
        }

        function connectWebSocket() {
            if (isReconnecting || intentionallyClosed) {
                return;
            }

            socket = createWebSocket('/narthex/socket/dataset');

            socket.onopen = function () {
                console.log("WebSocket connected");
                socket.send("user arrived on datasets page");
                reconnectAttempts = 0;
                reconnectDelay = 1000;
                isReconnecting = false;

                startHeartbeat();

                $scope.$apply(function() {
                    $scope.websocketConnected = true;
                    $scope.websocketError = null;
                });
            };

            socket.onmessage = function (messageReturned) {
                var message = JSON.parse(messageReturned.data);
                console.log("Received websocket msg: " + message);
                var callback = $scope.socketSubscribers[message.datasetSpec];
                if (callback) {
                    // Dataset is expanded - use its specific callback
                    callback(message);
                } else {
                    // Dataset is not expanded - handle update globally
                    console.debug("Handling update for collapsed dataset: " + message.datasetSpec);
                    $scope.$apply(function() {
                        // Find the dataset in the list
                        var datasetIndex = _.findIndex($scope.datasets, function(ds) {
                            return ds.datasetSpec === message.datasetSpec || ds.spec === message.datasetSpec;
                        });

                        if (datasetIndex !== -1) {
                            // Dataset exists in list - update it
                            var existingDataset = $scope.datasets[datasetIndex];

                            // Track previous operation to detect harvest completion
                            var previousOperation = existingDataset.currentOperation;
                            var previousAcquiredCount = existingDataset.acquiredRecordCount || existingDataset.datasetRecordCount;

                            // If it's a lightweight dataset, merge the update
                            if (existingDataset.isLight && !existingDataset.fullDataLoaded) {
                                // Update lightweight fields
                                if (message.processedValid !== undefined) existingDataset.processedValid = message.processedValid;
                                if (message.processedInvalid !== undefined) existingDataset.processedInvalid = message.processedInvalid;
                                if (message.datasetRecordCount !== undefined) existingDataset.recordCount = message.datasetRecordCount;

                                // Update all state timestamps (including disabled and intermediate states)
                                ['stateDisabled', 'stateEmpty', 'stateRaw', 'stateRawAnalyzed', 'stateSourced',
                                 'stateMappable', 'stateProcessable', 'stateAnalyzed', 'stateProcessed',
                                 'stateSaved', 'stateIncrementalSaved', 'stateInError'].forEach(function(stateProp) {
                                    if (message[stateProp] !== undefined) {
                                        existingDataset[stateProp] = message[stateProp];
                                    }
                                });

                                // Update delimiter fields
                                if (message.delimitersSet !== undefined) existingDataset.delimitersSet = message.delimitersSet;
                                if (message.recordRoot !== undefined) existingDataset.recordRoot = message.recordRoot;
                                if (message.uniqueId !== undefined) existingDataset.uniqueId = message.uniqueId;

                                // Update operation status fields
                                var operationChanged = false;
                                if (message.currentOperation !== undefined) {
                                    operationChanged = existingDataset.currentOperation !== message.currentOperation;
                                    existingDataset.currentOperation = message.currentOperation;
                                }
                                if (message.operationStatus !== undefined) existingDataset.operationStatus = message.operationStatus;
                                // Only update error fields with truthy values - don't clear existing errors with null
                                if (message.errorMessage) existingDataset.errorMessage = message.errorMessage;
                                if (message.errorTime) existingDataset.errorTime = message.errorTime;

                                // Re-decorate to update computed properties
                                $scope.decorateDatasetLight(existingDataset);

                                // If operation started or completed, update active count immediately
                                if (operationChanged) {
                                    $scope.updateActiveDatasets();
                                }
                            } else {
                                // Full dataset - merge update into existing object to preserve UI state
                                var operationChanged = existingDataset.currentOperation !== message.currentOperation;
                                // Use angular.extend to merge properties without replacing the object
                                angular.extend(existingDataset, message);
                                // Re-decorate to update computed properties
                                $scope.decorateDataset(existingDataset);

                                // If operation started or completed, update active count immediately
                                if (operationChanged) {
                                    $scope.updateActiveDatasets();
                                }
                            }

                            // Update state counters
                            $scope.updateDatasetStateCounter();

                            // Check for zero-record harvest completion
                            // Detect: harvest operation just ended and state is RAW (not SOURCED)
                            // If harvest completed with records, state would be SOURCED, not RAW
                            var wasHarvesting = previousOperation && previousOperation.indexOf('HARVEST') !== -1;
                            var operationEnded = !existingDataset.currentOperation;
                            var isRawState = existingDataset.stateRaw && !existingDataset.stateSourced;

                            // Debug logging
                            if (wasHarvesting) {
                                console.log("Harvest completed for " + existingDataset.datasetSpec +
                                    ", operationEnded=" + operationEnded +
                                    ", isRawState=" + isRawState +
                                    ", stateRaw=" + existingDataset.stateRaw +
                                    ", stateSourced=" + existingDataset.stateSourced +
                                    ", acquiredRecordCount=" + existingDataset.acquiredRecordCount);
                            }

                            if (wasHarvesting && operationEnded && isRawState) {
                                console.log("Detected zero-record harvest completion for " + existingDataset.datasetSpec);
                                modalAlert.confirm(
                                    "Sample Harvest: 0 Records",
                                    "The endpoint returned 0 records. This may indicate:\n\n" +
                                    "• Incorrect harvest URL\n" +
                                    "• Empty source\n" +
                                    "• Authentication required\n\n" +
                                    "Do you want to reset the record counts to 0?",
                                    function() {
                                        // User clicked "Yes" - reset counts via API call
                                        datasetListService.command(existingDataset.datasetSpec, "reset counts");
                                    }
                                );
                            }
                        } else {
                            // Dataset not in current list (might be filtered out)
                            console.debug("Dataset " + message.datasetSpec + " not in current view");
                        }
                    });
                }
            };

            socket.onerror = function (error) {
                console.error("WebSocket error:", error);
                $scope.$apply(function() {
                    $scope.websocketConnected = false;
                });
            };

            socket.onclose = function (event) {
                console.log("WebSocket closed:", event.code, event.reason);
                stopHeartbeat();

                $scope.$apply(function() {
                    $scope.websocketConnected = false;
                });

                if (intentionallyClosed) {
                    return;
                }

                if (reconnectAttempts < maxReconnectAttempts) {
                    isReconnecting = true;
                    reconnectAttempts++;
                    var delay = Math.min(reconnectDelay * Math.pow(2, reconnectAttempts - 1), 30000);
                    console.log("Reconnecting in " + delay + "ms (attempt " + reconnectAttempts + "/" + maxReconnectAttempts + ")");

                    setTimeout(function() {
                        isReconnecting = false;
                        connectWebSocket();
                    }, delay);
                } else {
                    console.error("Max reconnection attempts reached");
                    $scope.$apply(function() {
                        $scope.websocketError = "Connection lost. Please refresh the page.";
                    });
                }
            };
        }

        $scope.websocketConnected = false;
        connectWebSocket();

        $scope.$on('$destroy', function () {
            intentionallyClosed = true;
            stopHeartbeat();
            if (socket && socket.readyState === WebSocket.OPEN) {
                socket.send("user left datasets page");
                socket.close();
            }
            reconnectAttempts = maxReconnectAttempts;
        });

        $scope.subscribe = function (spec, callback) {
            $scope.socketSubscribers[spec] = callback;
        };

        $scope.unsubscribe = function (spec) {
            $scope.socketSubscribers[spec] = undefined;
        };

        function checkNewEnabled() {
            if ($scope.newDataset.specTyped)
                $scope.newDataset.spec = $scope.newDataset.specTyped.trim().replace(/\W+/g, "-").replace(/[-_]+/g, "-").toLowerCase();
            else
                $scope.newDataset.spec = "";
            $scope.newDataset.enabled = $scope.newDataset.spec.length && $scope.newDataset.character;
        }

        $scope.$watch("newDataset.specTyped", checkNewEnabled);
        $scope.$watch("newDataset.character", checkNewEnabled);
	
        /********************************************************************/	
        /* dataset filtering                                                */	
        /********************************************************************/	
	
        // Helper: check if dataset matches spec/name filter
        function matchesSpecFilter(ds, filter) {
            if (!filter) {
                return true;
            }
            var specMatches = ds.datasetSpec && ds.datasetSpec.toLowerCase().indexOf(filter) >= 0;
            var nameMatches = ds.datasetName && ds.datasetName.toLowerCase().indexOf(filter) >= 0;
            return specMatches || nameMatches;
        }	
	
        // Helper: check if dataset matches state filter
        function matchesStateFilter(ds, filter) {
            if (!filter) {
                return true;
            } else if (filter === 'stateWorking') {
                return ds.isProcessing === true || ds.isSaving === true;
            } else if (filter === 'stateQueued') {
                // Exclude datasets that are processing/saving (they've moved past queued state)
                return ds.isQueued === true && !ds.isProcessing && !ds.isSaving;
            } else if (filter === 'stateEmpty') {
                return ds.empty;
            } else {
                var currentState = ds.stateCurrentForFilter || ds.stateCurrent;
                return currentState.name === filter;
            }
        }

        // Unified filter: applies BOTH filters atomically to prevent flickering
        function applyAllFilters(ds) {
            var specFilter = $scope.specOrNameFilter ? $scope.specOrNameFilter.trim().toLowerCase() : '';
            var stateFilter = $scope.stateFilter;
            ds.visible = matchesSpecFilter(ds, specFilter) && matchesStateFilter(ds, stateFilter);
        }

        // Apply all filters to all datasets
        function applyFiltersToAll() {
            _.each($scope.datasets, applyAllFilters);
        }	
	
        $scope.datasetListOrder = function (orderBy) {	
            switch (orderBy) {	
                case "state":	
                    $scope.datasets = _.sortBy($scope.datasets, function (ds) {return ds.stateCurrent.name});	
                    $scope.currentSortOrder = orderBy;	
                    break;	
                case "lastmodified":	
                    $scope.datasets = _.sortBy($scope.datasets, function (ds) {return ds.stateCurrent.date}).reverse();	
                    $scope.currentSortOrder = orderBy;	
                    break;	
                default:	
                    $scope.datasets = _.sortBy($scope.datasets, 'datasetSpec');	
                    $scope.currentSortOrder = 'spec';	
                    break;	
            }	
            //console.log($scope.datasets)	
        };	
	
        $scope.setStateFilter = function(state){
            $scope.stateFilter = state;
            //filterDatasetByState(ds)
        };

        // Filter breadcrumb functions
        $scope.hasActiveFilters = function() {
            return ($scope.currentSortOrder && $scope.currentSortOrder !== 'spec') ||
                   $scope.stateFilter ||
                   $scope.specOrNameFilter;
        };

        $scope.getOrderLabel = function(order) {
            var labels = {
                'spec': 'Spec',
                'state': 'State',
                'lastmodified': 'Date last modified'
            };
            return labels[order] || order;
        };

        $scope.getStateLabel = function(state) {
            var stateObj = _.find($scope.datasetStates, {name: state});
            return stateObj ? stateObj.label : state;
        };

        $scope.clearOrderFilter = function() {
            $scope.datasetListOrder('spec'); // Reset to default
        };

        $scope.clearStateFilter = function() {
            $scope.stateFilter = '';
        };

        $scope.clearSpecFilter = function() {
            $scope.specOrNameFilter = '';
        };

        $scope.clearAllFilters = function() {
            $scope.clearOrderFilter();
            $scope.clearStateFilter();
            $scope.clearSpecFilter();
        };

        $scope.datasetVisibleFilter = function (ds) {
            return ds.visible;
        };	
	
        $scope.$watch("specOrNameFilter", applyFiltersToAll);

        $scope.$watch("stateFilter", applyFiltersToAll);	


        datasetListService.listPrefixes().then(function (prefixes) {
            $scope.characters = _.map(prefixes, function (prefix) {
                // for each prefix we should be able to accept a pre-mapped file
                return {
                    title: "Mapped to '" + prefix.toUpperCase() + "' format",
                    code: "character-mapped",
                    prefix: prefix
                };
            });
            if ($scope.characters.length == 1) {
                $scope.newDataset.character = $scope.characters[0];
            }
        });

        $scope.nonEmpty = function (obj) {
            return !_.isEmpty(obj)
        };

        $scope.isEmpty = function (obj) {
            return _.isEmpty(obj)
        };

        $scope.cancelNewFile = function () {
            $scope.newFileOpen = false;
        };

        $scope.setDropSupported = function () {
            $scope.dropSupported = true;
        };

        $scope.datasetStates = [
            {name: 'stateActive', label: 'Active', count: 0},
            {name: 'stateWorking', label: 'Working', count: 0},
            {name: 'stateQueued', label: 'Queued', count: 0},
            {name: 'stateEmpty', label: 'Empty', count: 0},
            {name: 'stateReadyToHarvest', label: 'Ready to Harvest', count: 0},
            {name: 'stateDisabled', label: 'Disabled', count: 0},
            {name: 'stateRaw', label: 'Raw', count: 0},
            {name: 'stateRawAnalyzed', label: 'Raw analyzed', count: 0},
            {name: 'stateSourced', label: 'Sourced', count: 0},
            {name: 'stateSourceAnalyzed', label: 'Source analyzed', count: 0},
            {name: 'stateMappable', label: 'Mappable', count: 0},
            {name: 'stateProcessable', label: 'Processable', count: 0},
            {name: 'stateInError', label: 'In error', count: 0},
            {name: 'stateProcessed', label: 'Processed', count: 0},
            {name: 'stateAnalyzed', label: 'Analyzed', count: 0},
            {name: 'stateSaved', label: 'Saved', count: 0},
            {name: 'stateIncrementalSaved', label: 'Incremental Saved', count: 0}
        ];

        /**
         * Decorate a dataset with minimal info (for collapsed view).
         * Used with lightweight dataset list.
         */
        $scope.decorateDatasetLight = function (dataset) {
            // Map lightweight property names to template property names
            dataset.datasetSpec = dataset.spec;
            dataset.datasetName = dataset.name;
            dataset.datasetRecordCount = dataset.recordCount;

            dataset.apiMappings = $scope.apiPrefix + dataset.spec + '/mappings';
            dataset.states = [];
            dataset.isLight = true; // Flag to indicate this is lightweight data
            dataset.fullDataLoaded = false;

            // Initialize dataset.edit for lightweight datasets
            // This is needed because DatasetEntryCtrl watches dataset.edit
            dataset.edit = angular.copy(dataset);

            // Process state timestamps from light data
            var stateVisible = false;
            _.forEach(
                $scope.datasetStates,
                function (state) {
                    var time = dataset[state.name];
                    if (time) {
                        stateVisible = true;
                        // Check if time is a string (raw timestamp) or already an object (already parsed)
                        if (typeof time === 'string') {
                            var dt = time.split('T');
                            dataset.states.push({"name": state.name, "date": Date.parse(time)});
                            dataset[state.name] = {
                                d: dt[0],
                                t: dt[1].split('+')[0],
                                dt: dt
                            };
                        } else {
                            // Already parsed, just update states array
                            dataset.states.push({"name": state.name, "date": Date.parse(time.d + 'T' + time.t)});
                        }
                    }
                }
            );

            if (!stateVisible) {
                dataset.empty = true;
            }

            dataset.stateCurrent = _.max(dataset.states, function (state) {
                return state.date
            });

            // Check if delimiters are valid (set after the latest analysis)
            // States that indicate delimiters have been set (you can't reach these without valid delimiters)
            var statesBeyondDelimit = ['stateSourced', 'stateMappable', 'stateProcessable',
                                       'stateProcessed', 'stateSaved', 'stateIncrementalSaved'];
            var rawAnalyzedState = _.find(dataset.states, function(s) { return s.name === 'stateRawAnalyzed'; });
            // Only count past-delimit states if they're NEWER than stateRawAnalyzed (i.e., current workflow run)
            var hasProgressedPastDelimit = _.some(dataset.states, function(s) {
                if (!_.contains(statesBeyondDelimit, s.name)) return false;
                // If re-analyzed, past states are stale - only valid if newer than rawAnalyzed
                return !rawAnalyzedState || s.date > rawAnalyzedState.date;
            });

            if (hasProgressedPastDelimit) {
                // Dataset has progressed past delimit stage in current workflow
                dataset.delimitersValid = true;
            } else if (dataset.delimitersSet) {
                // Check if delimitersSet is newer than stateRawAnalyzed
                var delimDate = new Date(dataset.delimitersSet);
                if (rawAnalyzedState) {
                    dataset.delimitersValid = delimDate.getTime() > rawAnalyzedState.date;
                } else {
                    dataset.delimitersValid = true;
                }
            } else {
                dataset.delimitersValid = false;
            }

            if (_.isEmpty(dataset.states)) {
                // Check if delimiters are valid - this means dataset is ready to harvest
                if (dataset.delimitersValid) {
                    dataset.stateCurrent = {"name": "stateReadyToHarvest", "date": Date.now()};
                } else {
                    dataset.stateCurrent = {"name": "stateEmpty", "date": Date.now()};
                }
            }

            // Check for error state (errorMessage comes from DsInfoLight)
            // Use truthiness check - null and undefined should not trigger error state
            if (dataset.errorMessage) {
                dataset.stateCurrent = {"name": "stateInError", "date": Date.now()};
            }

            // Disabled state takes precedence, BUT ignore stale disabled state
            // If any workflow state is >1 day newer than stateDisabled, consider it re-enabled
            if (dataset.stateDisabled) {
                // Get the parsed disabled date from states array (stateDisabled is already converted to object)
                var disabledState = _.find(dataset.states, function(s) { return s.name === 'stateDisabled'; });
                var disabledDate = disabledState ? disabledState.date : 0;
                var oneDayMs = 24 * 60 * 60 * 1000;
                var isStaleDisabled = _.some(dataset.states, function(s) {
                    return s.name !== 'stateDisabled' && s.date > (disabledDate + oneDayMs);
                });

                if (!isStaleDisabled) {
                    dataset.stateCurrent = {"name": "stateDisabled", "date": disabledDate};
                }
            }

            // Parse retry status (for lightweight datasets)
            dataset.inRetry = dataset.harvestInRetry === true || dataset.harvestInRetry === 'true';
            dataset.retryCount = parseInt(dataset.harvestRetryCount) || 0;
            dataset.retryMessage = dataset.harvestRetryMessage || '';

            // Calculate next retry time
            if (dataset.inRetry && dataset.harvestLastRetryTime) {
                var lastRetry = new Date(dataset.harvestLastRetryTime);
                var retryIntervalMinutes = ($scope.narthexConfig && $scope.narthexConfig.retryIntervalMinutes) || 60;
                var retryIntervalMs = retryIntervalMinutes * 60 * 1000;
                var nextRetry = new Date(lastRetry.getTime() + retryIntervalMs);
                var now = new Date();
                var diffMs = nextRetry - now;
                dataset.nextRetryMinutes = Math.max(0, Math.round(diffMs / 60000));
            }

            applyAllFilters(dataset);
            return dataset;
        };

        $scope.decorateDataset = function (dataset) {
            // Initialize error handling properties with defaults if not present
            // Must do this BEFORE copying to ensure dataset and dataset.edit match
            if (!dataset.harvestContinueOnError) {
                dataset.harvestContinueOnError = 'false';
            }
            if (!dataset.harvestErrorThreshold) {
                dataset.harvestErrorThreshold = 10;
            } else {
                // Backend returns as string, but <input type="number"> needs a number
                dataset.harvestErrorThreshold = parseInt(dataset.harvestErrorThreshold, 10);
            }

            // Parse retry status
            dataset.inRetry = dataset.harvestInRetry === 'true';
            dataset.retryCount = parseInt(dataset.harvestRetryCount) || 0;
            dataset.retryMessage = dataset.harvestRetryMessage || '';

            // Calculate next retry time
            if (dataset.inRetry && dataset.harvestLastRetryTime) {
                var lastRetry = new Date(dataset.harvestLastRetryTime);
                var retryIntervalMinutes = ($scope.narthexConfig && $scope.narthexConfig.retryIntervalMinutes) || 60;
                var retryIntervalMs = retryIntervalMinutes * 60 * 1000;
                var nextRetry = new Date(lastRetry.getTime() + retryIntervalMs);
                var now = new Date();
                var diffMs = nextRetry - now;
                dataset.nextRetryMinutes = Math.max(0, Math.round(diffMs / 60000));
            }

            // Normalize mapping source properties (handle both JSON-LD formats)
            // The JSON-LD may use full URIs or local names, so check both
            var nxPrefix = 'http://schemas.delving.eu/narthex/terms/';
            dataset.mappingSource = dataset.datasetMappingSource ||
                                    dataset[nxPrefix + 'datasetMappingSource'] ||
                                    dataset.mappingSource;
            dataset.defaultMappingPrefix = dataset.datasetDefaultMappingPrefix ||
                                           dataset[nxPrefix + 'datasetDefaultMappingPrefix'] ||
                                           dataset.defaultMappingPrefix;
            dataset.defaultMappingName = dataset.datasetDefaultMappingName ||
                                         dataset[nxPrefix + 'datasetDefaultMappingName'] ||
                                         dataset.defaultMappingName;
            dataset.defaultMappingVersion = dataset.datasetDefaultMappingVersion ||
                                            dataset[nxPrefix + 'datasetDefaultMappingVersion'] ||
                                            dataset.defaultMappingVersion;

            dataset.edit = angular.copy(dataset);

            dataset.apiMappings = $scope.apiPrefix + dataset.datasetSpec + '/mappings';
            dataset.states = [];
            dataset.fullDataLoaded = true; // Mark as having full data
//            if (dataset.character) dataset.prefix = info.character.prefix;
//            split the states into date and time
            var stateVisible = false;
            _.forEach(
                $scope.datasetStates,
                function (state) {
                    var time = dataset[state.name];
                    if (time) {
                        stateVisible = true;
                        // Check if time is a string (raw timestamp) or already an object (already parsed)
                        if (typeof time === 'string') {
                            var dt = time.split('T');
                            dataset.states.push({"name": state.name, "date": Date.parse(time)});
                            dataset[state.name] = {
                                d: dt[0],
                                t: dt[1].split('+')[0],
                                dt: dt
                            };
                        } else {
                            // Already parsed, just update states array
                            dataset.states.push({"name": state.name, "date": Date.parse(time.d + 'T' + time.t)});
                        }
                    }
                }
            );
            if (!stateVisible) {
                dataset.empty = true;
            };
            dataset.stateCurrent = _.max(dataset.states, function (state) {
                return state.date
            });

            // Check if delimiters are valid (set after the latest analysis)
            // States that indicate delimiters have been set (you can't reach these without valid delimiters)
            var statesBeyondDelimit = ['stateSourced', 'stateMappable', 'stateProcessable',
                                       'stateProcessed', 'stateSaved', 'stateIncrementalSaved'];
            var rawAnalyzedState = _.find(dataset.states, function(s) { return s.name === 'stateRawAnalyzed'; });
            // Only count past-delimit states if they're NEWER than stateRawAnalyzed (i.e., current workflow run)
            var hasProgressedPastDelimit = _.some(dataset.states, function(s) {
                if (!_.contains(statesBeyondDelimit, s.name)) return false;
                // If re-analyzed, past states are stale - only valid if newer than rawAnalyzed
                return !rawAnalyzedState || s.date > rawAnalyzedState.date;
            });

            if (hasProgressedPastDelimit) {
                // Dataset has progressed past delimit stage in current workflow
                dataset.delimitersValid = true;
            } else if (dataset.delimitersSet) {
                // Check if delimitersSet is newer than stateRawAnalyzed
                var delimDate = new Date(dataset.delimitersSet);
                if (rawAnalyzedState) {
                    dataset.delimitersValid = delimDate.getTime() > rawAnalyzedState.date;
                } else {
                    dataset.delimitersValid = true;
                }
            } else {
                dataset.delimitersValid = false;
            }

            if (_.isEmpty(dataset.states)) {
                // Check if delimiters are valid - this means dataset is ready to harvest
                if (dataset.delimitersValid) {
                    dataset.stateCurrent = {"name": "stateReadyToHarvest", "date": Date.now()};
                } else {
                    dataset.stateCurrent = {"name": "stateEmpty", "date": Date.now()};
                }
            }
            // Use truthiness check - null and undefined should not trigger error state
            if (dataset.datasetErrorMessage || dataset.errorMessage) {
                dataset.stateCurrent = {"name": "stateInError", "date": Date.now()};
            }
            // Disabled state takes precedence, BUT ignore stale disabled state
            // If any workflow state is >1 day newer than stateDisabled, consider it re-enabled
            if (dataset.stateDisabled) {
                // Get the parsed disabled date from states array (stateDisabled is already converted to object)
                var disabledState = _.find(dataset.states, function(s) { return s.name === 'stateDisabled'; });
                var disabledDate = disabledState ? disabledState.date : 0;
                var oneDayMs = 24 * 60 * 60 * 1000;
                var isStaleDisabled = _.some(dataset.states, function(s) {
                    return s.name !== 'stateDisabled' && s.date > (disabledDate + oneDayMs);
                });

                if (!isStaleDisabled) {
                    dataset.stateCurrent = {"name": "stateDisabled", "date": disabledDate};
                }
            }
            //console.log(dataset, dataset.stateCurrent, dataset.states, dataset.datasetErrorMessage)
            // showCounters removed from html: todo: remove?
            dataset.showCounters = _.some(dataset.states, function (state) {
               return state.name == 'stateProcessed' || state.name == 'stateIncrementalSaved';
            });
            dataset.showMapTerms = _.some(dataset.states, function (state) {
                return state.name == 'stateProcessed' || state.name == 'stateIncrementalSaved';
            });
            applyAllFilters(dataset);
            return dataset;
        };

        /**
         * Load full dataset details when expanding.
         * Only fetches if not already loaded.
         */
        $scope.expandDataset = function (dataset) {
            if (dataset.fullDataLoaded) {
                return; // Already have full data
            }

            // Fetch full dataset info
            datasetListService.datasetInfo(dataset.spec).then(function (fullData) {
                // Merge full data into the light dataset object
                angular.extend(dataset, fullData);
                // Mark as fully loaded so WebSocket updates don't overwrite with light data
                dataset.fullDataLoaded = true;
                dataset.isLight = false;
                // Re-decorate with full data
                $scope.decorateDataset(dataset);
            });
        };

        $scope.fetchDatasetList = function () {
            $scope.specOrNameFilter = "";
            // Use lightweight endpoint for faster initial load
            datasetListService.listDatasetsLight().then(function (array) {
                _.forEach(array, $scope.decorateDatasetLight);
                $scope.datasets = array;
                $scope.updateDatasetStateCounter();

                // Scroll to dataset if specified in URL parameter (from Index Stats / Trends)
                if ($routeParams.dataset) {
                    $timeout(function() {
                        var elementId = 'dataset-' + $routeParams.dataset;
                        var element = document.getElementById(elementId);
                        if (element) {
                            element.scrollIntoView({ behavior: 'smooth', block: 'center' });
                            // Add highlight effect
                            element.classList.add('highlight-dataset');
                            $timeout(function() {
                                element.classList.remove('highlight-dataset');
                            }, 2000);
                        }
                    }, 100);
                }
            });
        };

        $scope.fetchDatasetList();
        // if the url contains a hash with the dataset name, then be so nice as to scroll right to it.
        if($location.hash()){
            pageScroll.scrollTo({hash: $location.hash()});
        }

        // Track active datasets from server
        $scope.activeDatasets = {
            processing: [],
            saving: [],
            queued: [],
            queueLength: 0,
            availableSlots: 0,
            total: 0
        };

        // Completion stats for observability
        $scope.completionStats = null;
        $scope.completionDetails = [];
        $scope.uniqueDatasets24h = 0;
        $scope.showStatsPanel = false;

        $scope.toggleStatsPanel = function() {
            $scope.showStatsPanel = !$scope.showStatsPanel;
        };

        $scope.refreshStats = function() {
            datasetListService.listActiveDatasets().then(function(data) {
                $scope.completionStats = data.completionStats;
            });
        };

        $scope.updateActiveDatasets = function () {
            datasetListService.listActiveDatasets().then(function (data) {
                // Calculate total (processing + saving + queued)
                data.total = (data.processing || []).length +
                             (data.saving || []).length +
                             (data.queued || []).length;
                $scope.activeDatasets = data;

                // Update completion stats from response
                if (data.completionStats) {
                    $scope.completionStats = data.completionStats;
                }

                // Update completion details and calculate unique datasets
                if (data.completionDetails) {
                    $scope.completionDetails = data.completionDetails;
                    // Calculate unique datasets
                    var seen = {};
                    data.completionDetails.forEach(function(op) {
                        seen[op.spec] = true;
                    });
                    $scope.uniqueDatasets24h = Object.keys(seen).length;
                }

                // Build lookup maps for quick access
                var processingSet = {};
                var savingSet = {};
                var queuedMap = {};

                (data.processing || []).forEach(function(spec) {
                    processingSet[spec] = true;
                });
                (data.saving || []).forEach(function(spec) {
                    savingSet[spec] = true;
                });
                (data.queued || []).forEach(function(q) {
                    queuedMap[q.spec] = { trigger: q.trigger, position: q.position };
                });

                // Update each dataset with status info
                _.forEach($scope.datasets, function (ds) {
                    var spec = ds.datasetSpec;
                    ds.isProcessing = !!processingSet[spec];
                    ds.isSaving = !!savingSet[spec];
                    ds.isQueued = !!queuedMap[spec];
                    ds.queueInfo = queuedMap[spec] || null;
                    ds.isActive = ds.isProcessing || ds.isSaving;

                    // Set activity status for display
                    if (ds.isProcessing) {
                        ds.activityStatus = 'processing';
                        ds.activityTrigger = null;
                        ds.queuePosition = null;
                    } else if (ds.isSaving) {
                        ds.activityStatus = 'saving';
                        ds.activityTrigger = null;
                        ds.queuePosition = null;
                    } else if (ds.isQueued) {
                        ds.activityStatus = 'queued';
                        ds.activityTrigger = ds.queueInfo.trigger;
                        ds.queuePosition = ds.queueInfo.position;
                    } else {
                        ds.activityStatus = null;
                        ds.activityTrigger = null;
                        ds.queuePosition = null;
                    }

                    // If dataset is active or queued, override stateCurrent for filtering
                    // Keep stable timestamp to prevent list reordering on every poll
                    if (ds.isActive || ds.isQueued) {
                        // Only set new timestamp if dataset just became active
                        if (!ds.stateCurrentForFilter || ds.stateCurrentForFilter.name !== 'stateActive') {
                            ds.stateCurrentForFilter = {name: 'stateActive', date: Date.now()};
                        }
                        // else: keep existing stateCurrentForFilter to maintain stable sort order
                    } else {
                        ds.stateCurrentForFilter = ds.stateCurrent;
                    }
                });

                $scope.updateDatasetStateCounter();

                // Re-apply all filters to update visibility after state changes
                applyFiltersToAll();
            });
        };

        // Cancel a queued operation
        $scope.cancelQueuedOperation = function(spec) {
            datasetListService.cancelQueuedOperation(spec).then(function(response) {
                if (response.data && response.data.success) {
                    // Immediately update the UI
                    $scope.updateActiveDatasets();
                }
            });
        };

        // Interrupt a processing/saving operation
        $scope.interruptOperation = function(spec) {
            modalAlert.confirm("Interrupt Operation", "Interrupt processing for this dataset?", function() {
                datasetListService.command(spec, "interrupt").then(function() {
                    $scope.updateActiveDatasets();
                });
            });
        };

        // Poll for active datasets every 5 seconds
        $scope.updateActiveDatasets();
        var activeDatasetsInterval = setInterval(function () {
            $scope.updateActiveDatasets();
        }, 5000);

        // Index stats alert badge (wrong count + not indexed)
        $scope.indexStatsAlertCount = 0;
        $scope.updateIndexStatsAlertCount = function () {
            datasetListService.getIndexStatsWrongCount().then(function (data) {
                $scope.indexStatsAlertCount = data.totalAlerts || 0;
            }).catch(function () {
                // Silently ignore errors for badge polling
            });
        };

        // Poll for alert count every 5 minutes (less frequent than active datasets)
        $scope.updateIndexStatsAlertCount();
        var alertCountInterval = setInterval(function () {
            $scope.updateIndexStatsAlertCount();
        }, 300000); // 5 minutes

        // Clean up intervals on scope destruction
        $scope.$on('$destroy', function () {
            clearInterval(activeDatasetsInterval);
            clearInterval(alertCountInterval);
        });

        $scope.updateDatasetList = function (dataset) {
            $scope.datasets = _.map($scope.datasets, function (ds) {
                if (ds.datasetSpec == dataset.datasetSpec){
                    ds = dataset;
                }
                return ds;
            });
        };

        $scope.updateDatasetStateCounter = function () {
            var datasetStateCounter = _.countBy($scope.datasets, function (dataset) {
                // Use stateCurrentForFilter if available (includes active state), otherwise stateCurrent
                var state = dataset.stateCurrentForFilter || dataset.stateCurrent;
                return state.name;
            });

            // Count activity-based states separately
            var workingCount = 0;
            var queuedCount = 0;
            _.forEach($scope.datasets, function(dataset) {
                if (dataset.isProcessing || dataset.isSaving) workingCount++;
                if (dataset.isQueued) queuedCount++;
            });

            $scope.datasetStates = _.map(
                $scope.datasetStates,
                function (state) {
                    if (state.name === 'stateWorking') {
                        state.count = workingCount;
                    } else if (state.name === 'stateQueued') {
                        state.count = queuedCount;
                    } else if (_.has(datasetStateCounter, state.name)) {
                        state.count = datasetStateCounter[state.name];
                    } else {
                        state.count = 0;
                    }
                    return state;
                }
            );
        };

        $scope.createDataset = function () {
            datasetListService.create($scope.newDataset.spec, $scope.newDataset.character.code, $scope.newDataset.character.prefix).then(function () {
                $scope.cancelNewFile();
                $location.search("dataset", $scope.newDataset.spec);
                $scope.newDataset.specTyped = $scope.newDataset.spec = undefined;
                $scope.fetchDatasetList();
            });
        };

        $scope.fastSave = function(dataset, fromState) {
            console.log("fastSave called with dataset:", dataset, "fromState:", fromState);

            if (!dataset || !dataset.datasetSpec) {
                modalAlert.error("Error", "Invalid dataset");
                return;
            }

            // Determine steps based on specified state or current state
            // Note: Fast save uses incremental workflow (Process → Save, skips Analysis)
            var steps = [];
            var state = fromState || null;

            // If no state specified, auto-detect from current state
            if (!state) {
                if (dataset.stateAnalyzed) {
                    state = 'stateAnalyzed';
                } else if (dataset.stateProcessed) {
                    state = 'stateProcessed';
                } else if (dataset.stateProcessable) {
                    state = 'stateProcessable';
                } else if (dataset.stateSourced) {
                    state = 'stateSourced';
                }
            }

            // Map state to workflow steps
            if (state === 'stateAnalyzed') {
                steps = ["Save"];
            } else if (state === 'stateProcessed') {
                steps = ["Save"];
            } else if (state === 'stateProcessable') {
                steps = ["Process", "Save"];
            } else if (state === 'stateSourced') {
                steps = ["Make SIP", "Process", "Save"];
            } else {
                console.log("Dataset not in valid state for fast save");
                modalAlert.error("Error", "Dataset not ready for fast save");
                return;
            }

            var stepsList = steps.join(" → ");
            var confirmMessage = "Run workflow: " + stepsList + "\n\nfor dataset '" + dataset.datasetSpec + "'?";

            console.log("Showing confirm modal");
            modalAlert.confirm("Fast Save Confirmation", confirmMessage, function() {
                console.log("Confirm callback executed");
                // On confirm - execute fast save (no modal, just start silently)
                datasetListService.command(dataset.datasetSpec, "start fast save from " + state)
                    .then(function(reply) {
                        console.log("Fast save started: " + stepsList);
                    })
                    .catch(function(error) {
                        console.log("Fast save error:", error);
                        modalAlert.error("Fast Save Failed",
                            error.data && error.data.problem ? error.data.problem : error.statusText);
                    });
            });
        };

        $scope.openAllDatasets = false;
        $scope.$watch('openAllDatasets', function(newValue, oldValue, scope){
            var listRowHeaders = angular.element(document.getElementsByClassName("clickable"));
            var listRowItems = angular.element(document.getElementsByClassName("dataset-metadata"));
            if($scope.openAllDatasets == true) {
                listRowHeaders.removeClass('closed');
                listRowHeaders.addClass('open');
                listRowItems.removeClass('closed');
                listRowItems.addClass('expanded');
            } else {
                listRowHeaders.addClass('closed');
                listRowHeaders.removeClass('open');
                listRowItems.addClass('closed');
                listRowItems.removeClass('expanded');
            }
        });
    };

    DatasetListCtrl.$inject = [
        "$rootScope", "$scope", "datasetListService", "$location", "pageScroll", "modalAlert", "$timeout", "$routeParams"
    ];

    // these lists must match with DsInfo.scala

    var metadataFields = [
        "datasetName", "datasetDescription", "datasetAggregator", "datasetOwner", "datasetLanguage", "datasetRights", "datasetType", "datasetTags", "edmType", "datasetDataProviderURL"
    ];

    var harvestFields = [
        "harvestType", "harvestURL", "harvestDataset", "harvestPrefix", "harvestSearch", "harvestRecord", "harvestDownloadURL",
        "harvestContinueOnError", "harvestErrorThreshold", "harvestUsername", "harvestPassword",
        // JSON harvest fields
        "harvestJsonItemsPath", "harvestJsonIdPath", "harvestJsonTotalPath",
        "harvestJsonPageParam", "harvestJsonPageSizeParam", "harvestJsonPageSize",
        "harvestJsonDetailPath", "harvestJsonSkipDetail", "harvestJsonXmlRoot", "harvestJsonXmlRecord",
        "harvestApiKeyParam", "harvestApiKey"
    ];

    var harvestCronFields = [
        "harvestPreviousTime", "harvestDelay", "harvestDelayUnit", "harvestIncremental"
    ];

    var idFilterFields = [
        "idFilterType", "idFilterExpression"
    ];

    var publishFields = [
        "publishOAIPMH", "publishIndex", "publishLOD"
    ];

    var categoriesFields = [
        "categoriesInclude"
    ];

    var harvestingStatisticsFields = [
        "lastFullHarvestTime", "processedValid", "processedInvalid",
        "lastIncrementalHarvestTime", "processedIncrementalValid", "processedIncrementalInvalid"
    ];

    var DatasetEntryCtrl = function ($rootScope, $scope, datasetListService, $location, $timeout, $upload, $routeParams, modalAlert, $http, $modal) {
        if (!$scope.dataset) {
            modalAlert.error("Dataset Error", "No dataset specified!");
            return;
        }

        // Pass enableDefaultMappings to scope for conditional display
        $scope.enableDefaultMappings = $rootScope.enableDefaultMappings;

        // Function for tab switching (needed for mapping tab)
        $scope.setLeftTab = function(tabName) {
            $scope.leftTabOpen = tabName;
        };

        // Initialize activity modal state
        $scope.activityModal = { visible: false };
        $scope.subscribe($scope.dataset.datasetSpec, function (message) {
            function addProgressMessage(p) {
                var pre = progressStates[p.state] + " " + p.count.toString();
                var post = '';
                switch (p.type) {
                    case "progress-percent":
                        post = " %";
                        break;
                    case "progress-workers":
                        p.count = 100;
                        post = " worker(s)";
                        break;
                    case "progress-pages":
                        p.count = p.count % 100;
                        post = " page(s)";
                        break;
                }
                p.message = pre + post;
                if (p.count < 10) p.count = 10; // minimum space to write the text above
                return p;
            }

            $scope.datasetBusy = false;

            $scope.$apply(function () {
                if (message.progressState) {
                    $scope.dataset.progress = addProgressMessage({
                        state: message.progressState,
                        type: message.progressType,
                        count: parseInt(message.count),
                        currentPage: message.currentPage,
                        totalPages: message.totalPages,
                        currentRecords: message.currentRecords,
                        totalRecords: message.totalRecords
                    });
                    $scope.datasetBusy = true;
                }
                else {
                    console.log($scope.dataset);
                    // Preserve progress and previous operation from current dataset when receiving state updates
                    var existingProgress = $scope.dataset.progress;
                    var previousOperation = $scope.dataset.currentOperation;
                    $scope.dataset = $scope.decorateDataset(message);
                    // Restore progress if dataset is still active (has current operation)
                    if (existingProgress && $scope.dataset.currentOperation) {
                        $scope.dataset.progress = existingProgress;
                    }
                    $scope.updateDatasetList(message);
                    $scope.updateDatasetStateCounter();
                    $scope.datasetBusy = false;

                    // Check for zero-record harvest completion (same logic as collapsed handler)
                    var wasHarvesting = previousOperation && previousOperation.indexOf('HARVEST') !== -1;
                    var operationEnded = !$scope.dataset.currentOperation;
                    var isRawState = $scope.dataset.stateRaw && !$scope.dataset.stateSourced;

                    // Debug logging
                    if (wasHarvesting) {
                        console.log("(Expanded) Harvest completed for " + $scope.dataset.datasetSpec +
                            ", operationEnded=" + operationEnded +
                            ", isRawState=" + isRawState +
                            ", stateRaw=" + $scope.dataset.stateRaw +
                            ", stateSourced=" + $scope.dataset.stateSourced +
                            ", acquiredRecordCount=" + $scope.dataset.acquiredRecordCount);
                    }

                    if (wasHarvesting && operationEnded && isRawState) {
                        console.log("(Expanded) Detected zero-record harvest completion for " + $scope.dataset.datasetSpec);
                        modalAlert.confirm(
                            "Sample Harvest: 0 Records",
                            "The endpoint returned 0 records. This may indicate:\n\n" +
                            "• Incorrect harvest URL\n" +
                            "• Empty source\n" +
                            "• Authentication required\n\n" +
                            "Do you want to reset the record counts to 0?",
                            function() {
                                // User clicked "Yes" - reset counts via API call
                                datasetListService.command($scope.dataset.datasetSpec, "reset counts");
                            }
                        );
                    }
                }
            });
        });

        $scope.$on('$destroy', function () {
            $scope.unsubscribe($scope.dataset.spec);
        });
        $scope.leftTabOpen = "metadata";
        $scope.rightTabOpen = $scope.dataset.harvestURL ? "harvest" : "drop";
        $scope.expanded = $routeParams.dataset == $scope.dataset.datasetSpec;
        // If auto-expanded from URL, fetch full dataset data
        if ($scope.expanded) {
            $scope.expandDataset($scope.dataset);
        }
        $scope.idFilter = {};
        var baseUrl = angular.element("#content-wrapper").data("nave-url");
        $scope.searchLink = baseUrl + "/search?q=delving_spec:" + "\"" + $scope.dataset.datasetSpec + "\"";

        $scope.apiLink = baseUrl + "/api/search/v1/?q=delving_spec:" + $scope.dataset.datasetSpec;
        // todo: note that edm is hardcoded here:
        $scope.oaiPmhLink = baseUrl + "/api/oai-pmh?verb=ListRecords&metadataPrefix=edm&set=" + $scope.dataset.datasetSpec;
        $scope.apiPathErrors = $scope.apiPrefix + $scope.dataset.datasetSpec + "/errors";
        $scope.apiPathBulkActions = $scope.apiPrefix + $scope.dataset.datasetSpec + "/bulkactions";
        $scope.apiPathNquads = $scope.apiPrefix + $scope.dataset.datasetSpec + "/nquads";
        $scope.apiPathSourced = $scope.apiPrefix + $scope.dataset.datasetSpec + "/sourced";
        $scope.apiPathProcessed = $scope.apiPrefix + $scope.dataset.datasetSpec + "/processed";
        $scope.apiPathHarvestLog = $scope.apiPrefix + $scope.dataset.datasetSpec + "/log";
        $scope.apiDownloadSipZip = "/narthex/sip-app/" + $scope.dataset.datasetSpec;
        $scope.apiWebResourcePath = "/data/webresource/" + $scope.dataset.orgId + "/" + $scope.dataset.datasetSpec + "/source/";

        $scope.sparqlPath = baseUrl + "/snorql/?query=SELECT+%3Fs+%3Fp+%3Fo+%3Fg+WHERE+%7B%0D%0A++graph+%3Fg+%7B%0D%0A++++%3Fs1+%3Chttp%3A%2F%2Fcreativecommons.org%2Fns%23attributionName%3E+%22" + $scope.dataset.datasetSpec + "%22%0D%0A++%7D%0D%0A+++GRAPH+%3Fg+%7B%0D%0A++++++%3Fs+%3Fp+%3Fo+.%0D%0A+++%7D%0D%0A%7D%0D%0ALIMIT+50&format=browse";

        // Build preview URLs for harvest testing
        // URLs are encoded so that ? and & in the target URL become part of the path
        // rather than being interpreted as query parameters for the Narthex preview route
        function buildPreviewUrls() {
            if($scope.dataset.harvestURL && $scope.dataset.harvestType == 'pmh') {
                var pmhUrl;
                if ($scope.dataset.harvestRecord) {
                    pmhUrl = $scope.dataset.harvestURL.replace('?', '') + "?verb=GetRecord&metadataPrefix=" + $scope.dataset.harvestPrefix;
                    pmhUrl = pmhUrl + "&identifier=" + $scope.dataset.harvestRecord;
                } else if ($scope.dataset.harvestDataset) {
                    pmhUrl = $scope.dataset.harvestURL.replace('?', '') + "?verb=ListRecords&metadataPrefix=" + $scope.dataset.harvestPrefix;
                    pmhUrl = pmhUrl + "&set=" + $scope.dataset.harvestDataset;
                } else {
                    pmhUrl = $scope.dataset.harvestURL.replace('?', '') + "?verb=ListRecords&metadataPrefix=" + $scope.dataset.harvestPrefix;
                }
                // Store both raw and encoded versions
                $scope.pmhPreviewBase = pmhUrl;
                $scope.pmhPreviewEncoded = encodeURIComponent(pmhUrl);
            }
            if ($scope.dataset.harvestType == 'adlib' && $scope.dataset.harvestURL) {
                var adlibUrl = $scope.dataset.harvestURL.replace('?', '');
                if ($scope.dataset.harvestDataset) {
                    adlibUrl = adlibUrl + "?database=" + $scope.dataset.harvestDataset;
                }
                var connector = $scope.dataset.harvestDataset ? "&" : "?";
                var searchValue = $scope.dataset.harvestSearch ? $scope.dataset.harvestSearch : "all";
                adlibUrl = adlibUrl + connector + "search=" + searchValue;
                // Store both raw and encoded versions
                $scope.adlibPreviewBase = adlibUrl;
                $scope.adlibPreviewEncoded = encodeURIComponent(adlibUrl);
            }
        }

        // Build encoded preview URL with optional 'from' parameter for incremental harvest
        $scope.getIncrementalPreviewUrl = function() {
            if (!$scope.pmhPreviewBase) return '';
            var fromDate = $scope.trimMillis($scope.dataset.edit.harvestPreviousTime);
            if (!fromDate) return '/narthex/preview/' + encodeURIComponent($scope.pmhPreviewBase);
            var fullUrl = $scope.pmhPreviewBase + '&from=' + fromDate + 'Z';
            return '/narthex/preview/' + encodeURIComponent(fullUrl);
        };

        // Build preview URLs initially
        buildPreviewUrls();

        // Watch for harvestURL changes (when full data is loaded after lightweight initial load)
        $scope.$watch('dataset.harvestURL', function(newVal, oldVal) {
            if (newVal && newVal !== oldVal) {
                buildPreviewUrls();
            }
        });
        function setUnchanged() {
            // Safety check: ensure dataset.edit exists before comparing
            if (!$scope.dataset || !$scope.dataset.edit) {
                return;
            }

            function unchanged(fieldNameList) {
                var unchanged = true;
                _.forEach(fieldNameList, function (fieldName) {
                    if (!angular.equals($scope.dataset[fieldName], $scope.dataset.edit[fieldName])) {
//                    console.log("changed " + fieldName);
                        unchanged = false;
                    }
                });
                return unchanged;
            }

            $scope.unchangedMetadata = unchanged(metadataFields);
            $scope.unchangedPublish = unchanged(publishFields);
            $scope.unchangedHarvest = unchanged(harvestFields);
            $scope.unchangedHarvestCron = unchanged(harvestCronFields);
            $scope.unchangedIdFilter = unchanged(idFilterFields);
            $scope.unchangedCategories = unchanged(categoriesFields);
            $scope.incrementalPreview  = $scope.dataset['harvestPreviousTime'];
        }

        $scope.$watch("dataset.edit", setUnchanged, true); // deep comparison

        $scope.$watch("dataset", function (newDs) {
            //console.log("watched ds="+$scope.dataset.datasetSpec);
            setUnchanged()
        });

        $scope.$watch("expanded", function (expanded) {
            if (expanded) {
                $location.search("dataset", $scope.dataset.datasetSpec);
            }
        });

        $scope.receiveDropped = function ($files) {
            //$files: an array of files selected, each file has name, size, and type.  Take the first only.
            if (!($files.length && !$scope.dataset.uploading)) return;
            var onlyFile = $files[0];
            if (!(onlyFile.name.endsWith('.xml.gz') || onlyFile.name.endsWith('.xml') || onlyFile.name.endsWith('.csv') || onlyFile.name.endsWith('.sip.zip'))) {
                modalAlert.warning("Invalid File Type", "Sorry, the file must end with '.xml.gz', '.xml', '.csv' or '.sip.zip'");
                return;
            }
            $scope.dataset.uploading = true;
            $upload.upload({
                url: '/narthex/app/dataset/' + $scope.dataset.datasetSpec + '/upload',
                file: onlyFile
            }).progress(
                function (evt) {
                    if ($scope.dataset.uploading) $scope.dataset.uploadPercent = parseInt(100.0 * evt.loaded / evt.total);
                }
            ).success(
                function () {
                    $scope.dataset.uploading = false;
                    $scope.dataset.uploadPercent = null;
                }
            ).error(
                function (data, status, headers, config) {
                    $scope.dataset.uploading = false;
                    $scope.dataset.uploadPercent = null;
                    console.log("Failure during upload: data", data);
                    console.log("Failure during upload: status", status);
                    console.log("Failure during upload: headers", headers);
                    console.log("Failure during upload: config", config);
                    modalAlert.error("Upload Failed", data.problem || "An error occurred during upload");
                }
            );
        };

        $scope.showMetadataSubmitSuccess = false;

        function setProperties(propertyList) {
            var payload = {propertyList: propertyList, values: {}};
            _.forEach(propertyList, function (propertyName) {
                var value = angular.copy($scope.dataset.edit[propertyName]);
                // Backend expects all values as strings - convert numbers to strings
                if (typeof value === 'number') {
                    value = value.toString();
                }
                payload.values[propertyName] = value;
                //console.log(propertyName, value);
            });
            datasetListService.setDatasetProperties($scope.dataset.datasetSpec, payload);
            $scope.showMetadataSubmitSuccess = true;
        }

        $scope.submitMetadataForm = function (metadataform) {
            if (metadataform.$valid) {
                setProperties(metadataFields);
            }
        }

        $scope.setMetadata = function () {
            setProperties(metadataFields);
        };

        $scope.setPublish = function () {
            setProperties(publishFields);
        };

        $scope.setCategories = function () {
            setProperties(categoriesFields)
        };

        $scope.setHarvest = function () {
            // Filter out harvestPassword and harvestApiKey if empty and already set
            // This prevents overwriting existing credentials with empty values
            var fieldsToSave = _.filter(harvestFields, function(field) {
                if (field === 'harvestPassword') {
                    var passwordValue = $scope.dataset.edit.harvestPassword;
                    var passwordIsEmpty = !passwordValue || passwordValue === '';
                    var passwordAlreadySet = $scope.dataset.harvestPasswordSet;
                    // Don't include password field if empty AND password already exists
                    if (passwordIsEmpty && passwordAlreadySet) {
                        return false;
                    }
                }
                if (field === 'harvestApiKey') {
                    var apiKeyValue = $scope.dataset.edit.harvestApiKey;
                    var apiKeyIsEmpty = !apiKeyValue || apiKeyValue === '';
                    var apiKeyAlreadySet = $scope.dataset.harvestApiKeySet;
                    // Don't include API key field if empty AND API key already exists
                    if (apiKeyIsEmpty && apiKeyAlreadySet) {
                        return false;
                    }
                }
                return true;
            });
            setProperties(fieldsToSave);
        };

        $scope.setHarvestCron = function () {
            setProperties(harvestCronFields);
        };

        $scope.trimMillis = function (time) {
            if (!time) {
                return time
            }
            var replacement = time.replace(/\.[0-9]+/, '')
            console.log(time, replacement)
            return replacement
        };

        $scope.setIdFilter = function () {
            setProperties(idFilterFields);
        };

        $scope.isLater = function (currState, nextState) {
            if (!nextState) return true;
            if (currState) return currState.dt > nextState.dt;
            return false;
        };

        $scope.isCurrent = function (currState) {
            return currState == $scope.dataset.stateCurrent.name;
        };

        $scope.showLastSourcedPage = function () {
            window.open($scope.apiPathSourced, "_blank")
        };

        $scope.showLastProcessedPage = function () {
            window.open($scope.apiPathProcessed, "_blank")
        };

        $scope.resetDatasetState = function () {
            console.log("Resetting the dataset state.");
            command("resetToDormant", "reset state?");
        };

        $scope.showInvalidRecordsPage = function () {
            window.open($scope.apiPathErrors, "_blank")
        };


        $scope.showsBulkActionsPage = function () {
            window.open($scope.apiPathBulkActions, "_blank")
        };

        $scope.showsNquadsPage = function () {
            window.open($scope.apiPath, "_blank")
        };

        $scope.showHarvestingLog = function () {
            window.open($scope.apiPathHarvestLog, "_blank")
        };

        $scope.goToDataset = function () {
            // If raw analyzed but delimiters not valid, go to delimiter setting page
            if ($scope.dataset.stateRawAnalyzed && !$scope.dataset.delimitersValid) {
                $location.path("/dataset-delimiter/" + $scope.dataset.datasetSpec);
            } else {
                $location.path("/dataset/" + $scope.dataset.datasetSpec);
            }
        };

        $scope.goToSparql = function () {
            window.open($scope.sparqlPath, "_blank")
        };

        $scope.goToTerms = function () {
            $location.path("/terms/" + $scope.dataset.datasetSpec);
        };

        $scope.goToCategories = function () {
            $location.path("/categories/" + $scope.dataset.datasetSpec);
        };

        function command(command, areYouSure, after) {
            function executeCommand() {
                datasetListService.command($scope.dataset.datasetSpec, command).then(function (reply) {
                    console.log("command: " + command + " has reply: " + reply + " (" + $scope.dataset.datasetSpec + ")");
                    // Trigger a refresh command to update the dataset state in UI
                    return datasetListService.command($scope.dataset.datasetSpec, "refresh");
                }).then(function () {
                    if (after) after();
                });
            }

            if (areYouSure) {
                modalAlert.confirm("Confirm Action", areYouSure, executeCommand);
            } else {
                executeCommand();
            }
        }

        $scope.interruptProcessing = function () {
            command("interrupt", "Interrupt processing?");
        };

        $scope.clearError = function () {
            command("clear error", null);
        };

        $scope.retryNow = function () {
            command("retry now", null);
        };

        $scope.stopRetrying = function () {
            command("stop retrying", "Stop automatic retry and show error?");
        };

        $scope.deleteDataset = function () {
            command("delete", "Delete dataset?", function () {
                $timeout($scope.fetchDatasetList, 2000);
            });
        };

        $scope.start = function (commandMessage, question) {
            $scope.datasetBusy = true;
            command(commandMessage, question);
        };

        $scope.remove = function (commandMessage, question) {
            command(commandMessage, question);
        };

        /**
         * View the source analysis tree in a new window.
         * Opens the source-index JSON endpoint for the dataset.
         */
        $scope.viewSourceAnalysis = function (dataset) {
            var spec = dataset ? dataset.datasetSpec : $scope.dataset.datasetSpec;
            $location.path('/dataset/' + spec).search({type: 'source'});
        };

        function fetchSipFileList() {
            datasetListService.listSipFiles($scope.dataset.datasetSpec).then(function (data) {
                $scope.sipFiles = (data && data.list && data.list.length) ? data.list : undefined;
            });
        }

        // fetchSipFileList();

        $scope.deleteSipZip = function () {
            datasetListService.deleteLatestSipFile($scope.dataset.datasetSpec).then(function () {
                fetchSipFileList();
            });
        };

        $scope.openActivityModal = function () {
            console.log('Opening activity modal for dataset:', $scope.dataset.datasetSpec);

            // Create child scope for the modal
            var modalScope = $scope.$new();
            modalScope.spec = $scope.dataset.datasetSpec;
            modalScope.loading = true;
            modalScope.activities = [];
            modalScope.error = null;

            // Helper functions for the template
            modalScope.formatTimestamp = function(timestamp) {
                if (!timestamp) return '-';
                var date = new Date(timestamp);
                return date.toLocaleString();
            };

            modalScope.formatDuration = function(seconds) {
                if (seconds === null || seconds === undefined) return '-';
                if (seconds < 60) return seconds.toFixed(1) + 's';
                var minutes = Math.floor(seconds / 60);
                var secs = Math.floor(seconds % 60);
                return minutes + 'm ' + secs + 's';
            };

            modalScope.getStatusClass = function(status) {
                if (!status) return 'label-default';
                switch(status.toLowerCase()) {
                    case 'success': return 'label-success';
                    case 'completed': return 'label-success';
                    case 'failed': return 'label-danger';
                    case 'error': return 'label-danger';
                    case 'in progress': return 'label-info';
                    case 'running': return 'label-info';
                    default: return 'label-default';
                }
            };

            modalScope.getOperationLabel = function(activity) {
                return activity.operation || 'Workflow';
            };

            modalScope.getTriggerLabel = function(activity) {
                if (activity.trigger === 'manual') return 'Manual';
                if (activity.trigger === 'scheduled') return 'Scheduled';
                if (activity.trigger === 'cron') return 'Cron';
                return activity.trigger || '-';
            };

            modalScope.toggleWorkflow = function(activity) {
                activity.expanded = !activity.expanded;
            };

            modalScope.close = function() {
                modalScope.$destroy();
                $scope.activityModal.visible = false;
            };

            // Show the modal
            $scope.activityModal = { visible: true, scope: modalScope };
            console.log('Modal visibility set to:', $scope.activityModal.visible);

            // Fetch activity log
            var activityUrl = $scope.apiPrefix + $scope.dataset.datasetSpec + "/activity";
            console.log('Fetching activity log from:', activityUrl);
            $http.get(activityUrl, { transformResponse: function(data) { return data; } }).then(
                function(response) {
                    console.log('Activity log received, parsing JSONL...');
                    modalScope.loading = false;
                    // Parse JSONL (JSON Lines format)
                    var lines = response.data.trim().split('\n');
                    var activities = lines.map(function(line) {
                        try {
                            var activity = JSON.parse(line);
                            // Mark workflows for special rendering
                            if (activity.steps && activity.steps.length > 0) {
                                activity.isWorkflow = true;
                                activity.expanded = false;
                            }
                            return activity;
                        } catch(e) {
                            return null;
                        }
                    }).filter(function(item) { return item !== null; });

                    // Reverse to show newest first
                    modalScope.activities = activities.reverse();
                },
                function(error) {
                    modalScope.loading = false;
                    modalScope.error = "Failed to load activity log: " + (error.statusText || "Unknown error");
                }
            );
        };

        function executeIdFilter() {
            // Safety check: ensure dataset.edit exists before accessing properties
            if (!$scope.dataset || !$scope.dataset.edit) {
                return;
            }

            var expression = $scope.dataset.edit.idFilterExpression || '';
            var delimiter = ":::";
            var divider = expression.indexOf(delimiter);
            if (divider < 0) {
                $scope.idFilter.input = "";
                $scope.idFilter.error = "No divider";
                $scope.idFilter.output = "";
            }
            else {
                $scope.idFilter.error = "";
                var regExp = new RegExp(expression.substring(0, divider), 'g');
                var replacement = expression.substring(divider + delimiter.length);
                if($scope.idFilter.input){
                    $scope.idFilter.output = $scope.idFilter.input.replace(regExp, replacement);
                }
            }
        }

        $scope.$watch("idFilter.input", executeIdFilter);
        $scope.$watch("dataset.edit.idFilterExpression", executeIdFilter);

        // ==================== Mapping Source Functions ====================

        // Initialize mapping source state using an object to avoid ng-switch scope issues
        // Note: Use normalized property names (mappingSource, defaultMappingPrefix, etc.)
        // which are set in decorateDataset from various JSON-LD formats
        $scope.mapping = {
            source: $scope.dataset.mappingSource || 'manual',
            defaultPrefix: $scope.dataset.defaultMappingPrefix || '',
            defaultName: $scope.dataset.defaultMappingName || '',
            defaultVersion: $scope.dataset.defaultMappingVersion || 'latest',
            selectedDefault: '',  // Combined "prefix/name" value for dropdown
            useSpecificVersion: false,
            defaultMappings: [],        // List of all mappings for dropdown
            defaultVersions: [],        // Versions for selected mapping
            datasetVersions: []         // Dataset's own mapping versions
        };
        $scope.datasetSchemaPrefix = $scope.dataset.datasetMapToPrefix || '';

        // Initialize selected mapping from saved state
        if ($scope.mapping.defaultPrefix && $scope.mapping.defaultName) {
            $scope.mapping.selectedDefault = $scope.mapping.defaultPrefix + '/' + $scope.mapping.defaultName;
            if ($scope.mapping.defaultVersion && $scope.mapping.defaultVersion !== 'latest') {
                $scope.mapping.useSpecificVersion = true;
            }
        }

        // Re-initialize mapping state when full dataset data is loaded
        $scope.$watch('dataset.fullDataLoaded', function(newVal, oldVal) {
            if (newVal && !oldVal) {
                // Full data just loaded - update mapping state from normalized dataset properties
                $scope.mapping.source = $scope.dataset.mappingSource || 'manual';
                $scope.mapping.defaultPrefix = $scope.dataset.defaultMappingPrefix || '';
                $scope.mapping.defaultName = $scope.dataset.defaultMappingName || '';
                $scope.mapping.defaultVersion = $scope.dataset.defaultMappingVersion || 'latest';
                $scope.datasetSchemaPrefix = $scope.dataset.datasetMapToPrefix || '';

                if ($scope.mapping.defaultVersion && $scope.mapping.defaultVersion !== 'latest') {
                    $scope.mapping.useSpecificVersion = true;
                }

                // Reload default mappings list, then set the selection after options are loaded
                loadDefaultMappings().then(function() {
                    // Set selectedDefault after dropdown options are available
                    if ($scope.mapping.defaultPrefix && $scope.mapping.defaultName) {
                        $scope.mapping.selectedDefault = $scope.mapping.defaultPrefix + '/' + $scope.mapping.defaultName;
                        // Also load versions for the selected mapping
                        $scope.loadDefaultMappingVersions();
                    }
                });

                // Also load the dataset's own mapping versions (from SIP uploads)
                loadDatasetMappingVersions();
            }
        });

        // Load default mappings list (filtered by dataset's schema prefix)
        // Returns a promise that resolves when mappings are loaded
        function loadDefaultMappings() {
            // Update datasetSchemaPrefix in case full data was loaded after initialization
            $scope.datasetSchemaPrefix = $scope.dataset.datasetMapToPrefix || '';

            return $http.get('/narthex/app/default-mappings').then(function(response) {
                var allPrefixes = response.data.prefixes || [];
                var mappingsList = [];

                // Flatten the nested structure into a list for the dropdown
                allPrefixes.forEach(function(prefixData) {
                    // Filter by dataset's schema prefix if set
                    if ($scope.datasetSchemaPrefix && prefixData.prefix !== $scope.datasetSchemaPrefix) {
                        return;
                    }
                    (prefixData.mappings || []).forEach(function(m) {
                        mappingsList.push({
                            value: prefixData.prefix + '/' + m.name,
                            prefix: prefixData.prefix,
                            name: m.name,
                            displayName: m.displayName,
                            label: prefixData.prefix.toUpperCase() + ' - ' + m.displayName,
                            versionCount: m.versionCount
                        });
                    });
                });

                $scope.mapping.defaultMappings = mappingsList;
            });
        }

        // Load dataset mapping versions
        function loadDatasetMappingVersions() {
            $http.get('/narthex/app/dataset/' + $scope.dataset.datasetSpec + '/mapping-versions').then(function(response) {
                $scope.mapping.datasetVersions = response.data.versions || [];
            });
        }

        // Expose refresh function to scope for refresh button
        $scope.refreshMappingVersions = function() {
            loadDatasetMappingVersions();
        };

        // Load versions for selected default mapping
        $scope.loadDefaultMappingVersions = function() {
            if (!$scope.mapping.selectedDefault) {
                $scope.mapping.defaultVersions = [];
                $scope.mapping.defaultPrefix = '';
                $scope.mapping.defaultName = '';
                return;
            }

            // Parse the combined value
            var parts = $scope.mapping.selectedDefault.split('/');
            $scope.mapping.defaultPrefix = parts[0];
            $scope.mapping.defaultName = parts[1];

            $http.get('/narthex/app/default-mappings/' + $scope.mapping.defaultPrefix + '/' + $scope.mapping.defaultName).then(function(response) {
                $scope.mapping.defaultVersions = (response.data.versions || []).map(function(v) {
                    v.label = v.hash + ' (' + new Date(v.timestamp).toLocaleDateString() + ')';
                    if (v.notes) v.label += ' - ' + v.notes;
                    return v;
                });
            });
        };

        $scope.updateMappingSource = function() {
            // Only auto-save when switching to manual (to clear default mapping)
            if ($scope.mapping.source === 'manual') {
                $scope.saveDefaultMappingSelection();
            }
        };

        $scope.saveDefaultMappingSelection = function() {
            var version = $scope.mapping.useSpecificVersion ? $scope.mapping.defaultVersion : 'latest';
            var payload = {
                source: $scope.mapping.source,
                prefix: $scope.mapping.defaultPrefix || null,
                name: $scope.mapping.defaultName || null,
                version: version
            };
            console.log('saveDefaultMappingSelection - payload:', payload);
            $http.post('/narthex/app/dataset/' + $scope.dataset.datasetSpec + '/set-mapping-source', payload).then(function(response) {
                console.log('saveDefaultMappingSelection - response:', response.data);
                if (response.data.success) {
                    modalAlert.info("Mapping Source Updated", "Mapping source has been saved successfully.");
                    // Reload dataset mapping versions to reflect any changes
                    loadDatasetMappingVersions();
                }
            }, function(error) {
                console.error('saveDefaultMappingSelection - error:', error);
                modalAlert.error("Error", "Failed to save mapping source: " + (error.data && error.data.problem || "Unknown error"));
            });
        };

        $scope.previewDefaultMapping = function() {
            if (!$scope.mapping.defaultPrefix || !$scope.mapping.defaultName) return;
            var version = $scope.mapping.useSpecificVersion && $scope.mapping.defaultVersion ? $scope.mapping.defaultVersion : 'latest';
            $http.get('/narthex/app/default-mappings/' + $scope.mapping.defaultPrefix + '/' + $scope.mapping.defaultName + '/xml/' + version).then(function(response) {
                openXmlPreviewModal(response.data, 'Default Mapping: ' + $scope.mapping.defaultPrefix.toUpperCase() + ' - ' + $scope.mapping.defaultName);
            });
        };

        // Preview current dataset mapping (from SIP uploads or default mapping)
        $scope.previewCurrentMapping = function() {
            // First, check if dataset has its own mapping versions
            if ($scope.mapping.datasetVersions && $scope.mapping.datasetVersions.length > 0) {
                var currentVersion = null;
                for (var i = 0; i < $scope.mapping.datasetVersions.length; i++) {
                    if ($scope.mapping.datasetVersions[i].isCurrent) {
                        currentVersion = $scope.mapping.datasetVersions[i].hash;
                        break;
                    }
                }
                if (!currentVersion) {
                    currentVersion = $scope.mapping.datasetVersions[0].hash;
                }
                $scope.previewDatasetMapping(currentVersion);
                return;
            }

            // If no dataset versions, check if a default mapping is configured
            if ($scope.mapping.source === 'default' && $scope.mapping.defaultPrefix && $scope.mapping.defaultName) {
                $scope.previewDefaultMapping();
                return;
            }

            // No mapping available
            modalAlert.warning("No Mapping", "No mapping is configured for this dataset. Select a default mapping or upload a SIP with a mapping.");
        };

        // Check if any mapping is available for preview
        $scope.hasAnyMapping = function() {
            var hasDatasetVersions = $scope.mapping.datasetVersions && $scope.mapping.datasetVersions.length > 0;
            var hasDefaultMapping = $scope.mapping.source === 'default' && $scope.mapping.defaultPrefix && $scope.mapping.defaultName;

            // Has own dataset mapping versions (from SIP uploads)
            if (hasDatasetVersions) {
                return true;
            }
            // Has a configured default mapping
            if (hasDefaultMapping) {
                return true;
            }
            return false;
        };

        $scope.previewDatasetMapping = function(hash) {
            $http.get('/narthex/app/dataset/' + $scope.dataset.datasetSpec + '/mapping-xml/' + hash).then(function(response) {
                openXmlPreviewModal(response.data, 'Dataset Mapping: ' + $scope.dataset.datasetSpec + ' (' + hash + ')');
            });
        };

        // Helper function to open XML preview modal with proper formatting
        function openXmlPreviewModal(xmlContent, title) {
            $modal.open({
                templateUrl: '/narthex/assets/templates/xml-preview-modal.html',
                controller: 'XmlPreviewModalCtrl',
                size: 'lg',
                resolve: {
                    xmlContent: function() { return xmlContent; },
                    title: function() { return title; }
                }
            });
        }

        $scope.rollbackToVersion = function(hash) {
            if (!confirm('Are you sure you want to rollback to this mapping version? This will create a new version and switch to manual mode.')) {
                return;
            }
            $http.post('/narthex/app/dataset/' + $scope.dataset.datasetSpec + '/rollback-mapping/' + hash).then(function(response) {
                if (response.data.success) {
                    $scope.mappingSource = 'manual';
                    loadDatasetMappingVersions();
                    modalAlert.info("Rollback Complete", "Successfully rolled back to version " + hash);
                }
            }, function(error) {
                modalAlert.error("Error", "Failed to rollback: " + (error.data && error.data.problem || "Unknown error"));
            });
        };

        function escapeHtml(text) {
            return text
                .replace(/&/g, "&amp;")
                .replace(/</g, "&lt;")
                .replace(/>/g, "&gt;")
                .replace(/"/g, "&quot;")
                .replace(/'/g, "&#039;");
        }

        // Helper function to initialize mapping selection after loading options
        function initMappingSelection() {
            loadDefaultMappings().then(function() {
                // Restore selection from saved state after options are loaded
                if ($scope.mapping.defaultPrefix && $scope.mapping.defaultName) {
                    $scope.mapping.selectedDefault = $scope.mapping.defaultPrefix + '/' + $scope.mapping.defaultName;
                    $scope.loadDefaultMappingVersions();
                }
            });
            loadDatasetMappingVersions();
        }

        // Load mapping data when expanding dataset
        $scope.$watch('expanded', function(newVal) {
            if (newVal && $scope.leftTabOpen === 'mapping') {
                initMappingSelection();
            }
        });

        $scope.$watch('leftTabOpen', function(newVal) {
            if (newVal === 'mapping' && $scope.expanded) {
                initMappingSelection();
            }
        });

    };

    DatasetEntryCtrl.$inject = ["$rootScope", "$scope", "datasetListService", "$location", "$timeout", "$upload", "$routeParams", "modalAlert", "$http", "$modal"];

    /** Controls the sidebar and headers */
    var IndexCtrl = function ($rootScope, $scope, $location, $http) {

        $scope.initialize = function (orgId, sipCreatorLink, enableIncrementalHarvest, supportedDatasetTypes, enableDefaultMappings, enableDatasetDiscovery) {
            //console.log("Initializing index");
            $rootScope.orgId = orgId;
            $rootScope.sipCreatorLink = sipCreatorLink;
            $rootScope.enableIncrementalHarvest = enableIncrementalHarvest;
            $rootScope.supportedDatasetTypes = supportedDatasetTypes ? supportedDatasetTypes.split(",") : [];
            $rootScope.enableDefaultMappings = enableDefaultMappings === 'true';
            $scope.enableDefaultMappings = $rootScope.enableDefaultMappings;
            $rootScope.enableDatasetDiscovery = enableDatasetDiscovery === 'true';
            $scope.enableDatasetDiscovery = $rootScope.enableDatasetDiscovery;
            $scope.toggleBar = true;
        };

        $rootScope.sidebarNav = function (page, dataset) {

            var navlist = $('#sidebar-nav a');
            navlist.removeClass('active');

            if (page == '') {
                page = 'dataset-list'
            }
            console.log(page);
            switch (page) {
                case 'dataset-list':
                    if(dataset){
                        $location.search('dataset', dataset).hash(dataset).path('/');
                    }
                    else {
                        $location.path('/');
                    }
                    break;
                case 'skos':
                    $location.path('/skos');
                    break;
                case 'stats':
                    $location.path('/stats');
                    break;
                case 'index-stats':
                    $location.path('/index-stats');
                    break;
                case 'trends':
                    $location.path('/trends');
                    break;
                case 'categories':
                    $location.path('/categories');
                    break;
                case 'thesaurus':
                    $location.path('/thesaurus');
                    break;
                case 'default-mappings':
                    $location.path('/default-mappings');
                    break;
                case 'discovery':
                    $location.path('/discovery');
                    break;
                case 'sip-creator':
                    $location.path('/sip-creator');
                    break;
            }
            $('#nav-' + page).addClass('active');

        };

        $scope.toggleSidebar = function () {
            $scope.toggleBar = !$scope.toggleBar;
        };

        // Memory stats modal
        $scope.memoryStats = null;
        $scope.memoryStatsLoading = false;
        $scope.memoryStatsVisible = false;

        $scope.showMemoryStats = function() {
            $scope.memoryStatsVisible = true;
            $scope.memoryStatsLoading = true;
            $scope.memoryStats = null;

            $http.get('/narthex/app/memory-stats').then(
                function(response) {
                    $scope.memoryStats = response.data;
                    $scope.memoryStatsLoading = false;
                },
                function(error) {
                    $scope.memoryStatsLoading = false;
                    console.error('Failed to load memory stats:', error);
                }
            );
        };

        $scope.closeMemoryStats = function() {
            $scope.memoryStatsVisible = false;
        };

        $scope.resetMemoryStats = function() {
            $http.post('/narthex/app/memory-stats/reset').then(function() {
                $scope.showMemoryStats(); // Reload stats
            });
        };

        $scope.forceGC = function() {
            $http.post('/narthex/app/memory-stats/gc').then(function(response) {
                $scope.lastGCFreed = response.data.freedMB;
                $scope.showMemoryStats(); // Reload stats
            });
        };

    };

    IndexCtrl.$inject = ["$rootScope", "$scope", "$location", "$http"];

    /** Controls the SIP Creator Downloads page */
    var SipCreatorDownloadsCtrl = function ($scope, $http) {
        $scope.releases = [];
        $scope.snapshots = [];
        $scope.loading = true;
        $scope.error = null;
        $scope.activeTab = 'releases';

        // Load downloads on init
        $scope.loadDownloads = function(refresh) {
            $scope.loading = true;
            $scope.error = null;

            var url = '/narthex/api/sip-creator/downloads';
            if (refresh) {
                url += '?refresh=true';
            }

            $http.get(url).then(
                function(response) {
                    $scope.releases = response.data.releases || [];
                    $scope.snapshots = response.data.snapshots || [];

                    // Auto-expand first version in each list
                    if ($scope.releases.length > 0) {
                        $scope.releases[0].expanded = true;
                    }
                    if ($scope.snapshots.length > 0) {
                        $scope.snapshots[0].expanded = true;
                    }

                    $scope.loading = false;
                },
                function(error) {
                    $scope.error = "Failed to load downloads: " + (error.statusText || "Unknown error");
                    $scope.loading = false;
                }
            );
        };

        $scope.refresh = function() {
            $scope.loadDownloads(true);
        };

        $scope.setTab = function(tab) {
            $scope.activeTab = tab;
        };

        $scope.toggleVersion = function(version) {
            version.expanded = !version.expanded;
        };

        $scope.getOsIcon = function(os) {
            var icons = {
                'Linux': 'fa-linux',
                'macOS': 'fa-apple',
                'Windows': 'fa-windows',
                'universal': 'fa-coffee'
            };
            return icons[os] || 'fa-file';
        };

        // Initial load
        $scope.loadDownloads(false);
    };

    SipCreatorDownloadsCtrl.$inject = ["$scope", "$http"];

    return {
        IndexCtrl: IndexCtrl,
        DatasetListCtrl: DatasetListCtrl,
        DatasetEntryCtrl: DatasetEntryCtrl,
        SipCreatorDownloadsCtrl: SipCreatorDownloadsCtrl
    };

});
