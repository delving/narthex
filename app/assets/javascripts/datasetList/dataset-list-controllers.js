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

    var DatasetListCtrl = function ($rootScope, $scope, datasetListService, $location, pageScroll, modalAlert) {

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

                                // Update operation status fields
                                var operationChanged = false;
                                if (message.currentOperation !== undefined) {
                                    operationChanged = existingDataset.currentOperation !== message.currentOperation;
                                    existingDataset.currentOperation = message.currentOperation;
                                }
                                if (message.operationStatus !== undefined) existingDataset.operationStatus = message.operationStatus;
                                if (message.errorMessage !== undefined) existingDataset.errorMessage = message.errorMessage;
                                if (message.errorTime !== undefined) existingDataset.errorTime = message.errorTime;

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
	
        function filterDatasetBySpec(ds) {	
            var filter = $scope.specOrNameFilter.trim();	
            ds.visible = !filter || ds.datasetSpec.toLowerCase().indexOf(filter.toLowerCase()) >= 0;	
            if (ds.datasetName != null){	
                ds.visible = !filter || ds.datasetName.toLowerCase().indexOf(filter.toLowerCase()) >= 0;	
            }	
        }	
	
        function filterDatasetByState(ds) {	
            var filter = $scope.stateFilter;	
            if (filter != 'stateEmpty') {	
                var currentState = ds.stateCurrentForFilter || ds.stateCurrent;
                ds.visible = !filter || currentState.name === filter;	
            }	
            else {	
                ds.visible = !filter || ds.empty;	
            }	
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
	
        $scope.$watch("specOrNameFilter", function () {	
            _.each($scope.datasets, filterDatasetBySpec);	
        });	
	
        $scope.$watch("stateFilter", function () {	
            _.each($scope.datasets, filterDatasetByState);	
        });	


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
            {name: 'stateEmpty', label: 'Empty', count: 0},
            {name: 'stateDisabled', label: 'Disabled', count: 0},
            {name: 'stateRaw', label: 'Raw', count: 0},
            {name: 'stateRawAnalyzed', label: 'Raw analyzed', count: 0},
            {name: 'stateSourced', label: 'Sourced', count: 0},
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

            if (_.isEmpty(dataset.states)) {
                dataset.stateCurrent = {"name": "stateEmpty", "date": Date.now()};
            }

            // Check for error state (errorMessage comes from DsInfoLight)
            // Use truthiness check - null and undefined should not trigger error state
            if (dataset.errorMessage) {
                dataset.stateCurrent = {"name": "stateInError", "date": Date.now()};
            }

            filterDatasetBySpec(dataset);
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

            if (_.isEmpty(dataset.states)) {
                dataset.stateCurrent = {"name": "stateEmpty", "date": Date.now()};
            }
            // Use truthiness check - null and undefined should not trigger error state
            if (dataset.datasetErrorMessage || dataset.errorMessage) {
                dataset.stateCurrent = {"name": "stateInError", "date": Date.now()};
            }
            //console.log(dataset, dataset.stateCurrent, dataset.states, dataset.datasetErrorMessage)
            // showCounters removed from html: todo: remove?
            dataset.showCounters = _.some(dataset.states, function (state) {
               return state.name == 'stateProcessed' || state.name == 'stateIncrementalSaved';
            });
            dataset.showMapTerms = _.some(dataset.states, function (state) {
                return state.name == 'stateProcessed' || state.name == 'stateIncrementalSaved';
            });
            filterDatasetBySpec(dataset);
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

        $scope.updateActiveDatasets = function () {
            datasetListService.listActiveDatasets().then(function (data) {
                // Calculate total (processing + saving + queued)
                data.total = (data.processing || []).length +
                             (data.saving || []).length +
                             (data.queued || []).length;
                $scope.activeDatasets = data;

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
                    if (ds.isActive || ds.isQueued) {
                        ds.stateCurrentForFilter = {name: 'stateActive', date: Date.now()};
                    } else {
                        ds.stateCurrentForFilter = ds.stateCurrent;
                    }
                });

                $scope.updateDatasetStateCounter();

                // Re-apply the current filter to remove finished datasets from filtered view
                if ($scope.stateFilter) {
                    _.each($scope.datasets, filterDatasetByState);
                }
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

        // Poll for active datasets every 5 seconds
        $scope.updateActiveDatasets();
        var activeDatasetsInterval = setInterval(function () {
            $scope.updateActiveDatasets();
        }, 5000);

        // Clean up interval on scope destruction
        $scope.$on('$destroy', function () {
            clearInterval(activeDatasetsInterval);
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
            //var datasetErrorCounter = _.countBy($scope.datasets, function (dataset) {
            //   return _.isUndefined(dataset.errorMessage);
            //});
            //console.log(datasetErrorCounter);
            $scope.datasetStates = _.map(
                $scope.datasetStates,
                function (state) {
                    if (_.has(datasetStateCounter, state.name)) {
                        state.count = datasetStateCounter[state.name];
                    }
                    else {
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
                // On confirm - execute fast save
                modalAlert.info("Fast Save Started",
                    "Running: " + stepsList + "\n\n" +
                    "Monitor progress in the activity log.");

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
        "$rootScope", "$scope", "datasetListService", "$location", "pageScroll", "modalAlert"
    ];

    // these lists must match with DsInfo.scala

    var metadataFields = [
        "datasetName", "datasetDescription", "datasetAggregator", "datasetOwner", "datasetLanguage", "datasetRights", "datasetType", "datasetTags", "edmType", "datasetDataProviderURL"
    ];

    var harvestFields = [
        "harvestType", "harvestURL", "harvestDataset", "harvestPrefix", "harvestSearch", "harvestRecord", "harvestDownloadURL",
        "harvestContinueOnError", "harvestErrorThreshold"
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

    var DatasetEntryCtrl = function ($scope, datasetListService, $location, $timeout, $upload, $routeParams, modalAlert, $http) {
        if (!$scope.dataset) {
            modalAlert.error("Dataset Error", "No dataset specified!");
            return;
        }

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
                    $scope.dataset = $scope.decorateDataset(message);
                    $scope.updateDatasetList(message);
                    $scope.updateDatasetStateCounter();
                    $scope.datasetBusy = false;
                }
            });
        });

        $scope.$on('$destroy', function () {
            $scope.unsubscribe($scope.dataset.spec);
        });
        $scope.leftTabOpen = "metadata";
        $scope.rightTabOpen = $scope.dataset.harvestURL ? "harvest" : "drop";
        $scope.expanded = $routeParams.dataset == $scope.dataset.datasetSpec;
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
        function buildPreviewUrls() {
            if($scope.dataset.harvestURL && $scope.dataset.harvestType == 'pmh') {
                if ($scope.dataset.harvestRecord) {
                    $scope.pmhPreviewBase = $scope.dataset.harvestURL.replace('?', '') + "?verb=GetRecord&metadataPrefix=" + $scope.dataset.harvestPrefix;
                    $scope.pmhPreviewBase = $scope.pmhPreviewBase + "&identifier=" + $scope.dataset.harvestRecord;
                } else if ($scope.dataset.harvestDataset) {
                    $scope.pmhPreviewBase = $scope.dataset.harvestURL.replace('?', '') + "?verb=ListRecords&metadataPrefix=" + $scope.dataset.harvestPrefix;
                    $scope.pmhPreviewBase = $scope.pmhPreviewBase + "&set=" + $scope.dataset.harvestDataset;
                } else {
                    $scope.pmhPreviewBase = $scope.dataset.harvestURL.replace('?', '') + "?verb=ListRecords&metadataPrefix=" + $scope.dataset.harvestPrefix;
                }
            }
            if ($scope.dataset.harvestType == 'adlib' && $scope.dataset.harvestURL) {
                $scope.adlibPreviewBase = $scope.dataset.harvestURL.replace('?', '')
                if ($scope.dataset.harvestDataset) {
                    $scope.adlibPreviewBase = $scope.adlibPreviewBase + "?database=" +$scope.dataset.harvestDataset;
                }
                var connector = $scope.dataset.harvestDataset ? "&" : "?";
                var searchValue = $scope.dataset.harvestSearch ? $scope.dataset.harvestSearch : "all";
                $scope.adlibPreviewBase = $scope.adlibPreviewBase + connector + "search=" + searchValue ;
            }
        }

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
            setProperties(harvestFields);
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
            $location.path("/dataset/" + $scope.dataset.datasetSpec);
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
            if (areYouSure && !confirm(areYouSure)) return;
            datasetListService.command($scope.dataset.datasetSpec, command).then(function (reply) {
                console.log("command: " + command + " has reply: " + reply + " (" + $scope.dataset.datasetSpec + ")");
                // Trigger a refresh command to update the dataset state in UI
                return datasetListService.command($scope.dataset.datasetSpec, "refresh");
            }).then(function () {
                if (after) after();
            });
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

    };

    DatasetEntryCtrl.$inject = ["$scope", "datasetListService", "$location", "$timeout", "$upload", "$routeParams", "modalAlert", "$http"];

    /** Controls the sidebar and headers */
    var IndexCtrl = function ($rootScope, $scope, $location) {

        $scope.initialize = function (orgId, sipCreatorLink, enableIncrementalHarvest, supportedDatasetTypes) {
            //console.log("Initializing index");
            $rootScope.orgId = orgId;
            $rootScope.sipCreatorLink = sipCreatorLink;
            $rootScope.enableIncrementalHarvest = enableIncrementalHarvest;
            $rootScope.supportedDatasetTypes = supportedDatasetTypes ? supportedDatasetTypes.split(",") : [];
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
                case 'categories':
                    $location.path('/categories');
                    break;
                case 'thesaurus':
                    $location.path('/thesaurus');
                    break;
            }
            $('#nav-' + page).addClass('active');

        };

        $scope.toggleSidebar = function () {
            $scope.toggleBar = !$scope.toggleBar;
        };

    };

    IndexCtrl.$inject = ["$rootScope", "$scope", "$location"];

    return {
        IndexCtrl: IndexCtrl,
        DatasetListCtrl: DatasetListCtrl,
        DatasetEntryCtrl: DatasetEntryCtrl
    };

});
