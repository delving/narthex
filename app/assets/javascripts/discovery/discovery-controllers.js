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

    /**
     * Main Discovery Controller
     */
    var DiscoveryCtrl = function ($scope, $rootScope, $modal, discoveryService, defaultMappingsService) {
        // State
        $scope.sources = [];
        $scope.selectedSource = null;
        $scope.discoveryResult = null;
        $scope.discovering = false;
        $scope.importing = false;
        $scope.selectedSets = {};
        $scope.defaultMappings = [];
        $scope.availablePrefixes = [];

        // Load sources
        function loadSources() {
            discoveryService.listSources().then(function(sources) {
                $scope.sources = sources;
            });
        }

        // Load default mappings for the source config modal
        function loadDefaultMappings() {
            if (defaultMappingsService) {
                defaultMappingsService.listDefaultMappings().then(function(data) {
                    $scope.defaultMappings = data.prefixes || [];
                    $scope.availablePrefixes = data.availablePrefixes || [];
                });
            }
        }

        // Source management
        $scope.openSourceConfig = function(source) {
            var modalInstance = $modal.open({
                templateUrl: '/narthex/assets/templates/discovery-source-modal.html',
                controller: 'SourceConfigModalCtrl',
                size: 'lg',
                resolve: {
                    source: function() { return source ? angular.copy(source) : null; },
                    availablePrefixes: function() { return $scope.availablePrefixes; },
                    defaultMappings: function() { return $scope.defaultMappings; }
                }
            });

            modalInstance.result.then(function(result) {
                loadSources();
                if (result && result.id && $scope.selectedSource && $scope.selectedSource.id === result.id) {
                    $scope.selectedSource = result;
                }
            });
        };

        $scope.deleteSource = function(source) {
            if (confirm("Are you sure you want to delete '" + source.name + "'?")) {
                discoveryService.deleteSource(source.id).then(function() {
                    loadSources();
                    if ($scope.selectedSource && $scope.selectedSource.id === source.id) {
                        $scope.selectedSource = null;
                        $scope.discoveryResult = null;
                    }
                });
            }
        };

        // Discovery
        $scope.selectSource = function(source) {
            $scope.selectedSource = source;
            $scope.discoveryResult = null;
            $scope.selectedSets = {};
        };

        $scope.discover = function() {
            if (!$scope.selectedSource) return;

            $scope.discovering = true;
            $scope.discoveryResult = null;
            $scope.selectedSets = {};

            discoveryService.discoverSets($scope.selectedSource.id).then(function(result) {
                $scope.discoveryResult = result;
                $scope.discovering = false;
            }, function(error) {
                $scope.discovering = false;
                alert("Discovery failed: " + (error.data ? error.data.error : "Unknown error"));
            });
        };

        // Selection
        $scope.toggleSetSelection = function(set) {
            if ($scope.selectedSets[set.normalizedSpec]) {
                delete $scope.selectedSets[set.normalizedSpec];
            } else {
                $scope.selectedSets[set.normalizedSpec] = set;
            }
        };

        $scope.isSelected = function(set) {
            return !!$scope.selectedSets[set.normalizedSpec];
        };

        $scope.selectAllNew = function() {
            if (!$scope.discoveryResult) return;
            $scope.discoveryResult.newSets.forEach(function(set) {
                $scope.selectedSets[set.normalizedSpec] = set;
            });
        };

        $scope.clearSelection = function() {
            $scope.selectedSets = {};
        };

        $scope.getSelectedCount = function() {
            return Object.keys($scope.selectedSets).length;
        };

        $scope.getSelectedList = function() {
            return Object.keys($scope.selectedSets).map(function(key) {
                return $scope.selectedSets[key];
            });
        };

        // Ignore sets
        $scope.ignoreSelected = function() {
            var selectedList = $scope.getSelectedList();
            if (selectedList.length === 0) {
                alert("Please select at least one set to ignore.");
                return;
            }

            var setSpecs = selectedList.map(function(s) { return s.setSpec; });
            discoveryService.ignoreSets($scope.selectedSource.id, setSpecs).then(function() {
                $scope.clearSelection();
                $scope.discover(); // Refresh
            });
        };

        $scope.unignoreSet = function(set) {
            discoveryService.unignoreSets($scope.selectedSource.id, [set.setSpec]).then(function() {
                $scope.discover(); // Refresh
            });
        };

        // Import
        $scope.importSelected = function() {
            var selectedList = $scope.getSelectedList();
            if (selectedList.length === 0) {
                alert("Please select at least one set to import.");
                return;
            }

            // Open import confirmation modal
            var modalInstance = $modal.open({
                templateUrl: '/narthex/assets/templates/discovery-import-modal.html',
                controller: 'ImportConfirmModalCtrl',
                size: 'lg',
                resolve: {
                    selectedSets: function() { return selectedList; },
                    source: function() { return $scope.selectedSource; }
                }
            });

            modalInstance.result.then(function(importRequests) {
                $scope.importing = true;
                discoveryService.importSets(importRequests).then(function(result) {
                    $scope.importing = false;
                    alert("Import complete: " + result.imported + " imported, " + result.failed + " failed.");
                    $scope.clearSelection();
                    $scope.discover(); // Refresh
                }, function(error) {
                    $scope.importing = false;
                    alert("Import failed: " + (error.data ? error.data.error : "Unknown error"));
                });
            });
        };

        // Format date
        $scope.formatDate = function(dateStr) {
            if (!dateStr) return '-';
            var date = new Date(dateStr);
            return date.toLocaleDateString() + ' ' + date.toLocaleTimeString();
        };

        // Initialize
        loadSources();
        loadDefaultMappings();
    };

    DiscoveryCtrl.$inject = ['$scope', '$rootScope', '$modal', 'discoveryService', 'defaultMappingsService'];

    /**
     * Source Configuration Modal Controller
     */
    var SourceConfigModalCtrl = function ($scope, $modalInstance, discoveryService, source, availablePrefixes, defaultMappings) {
        $scope.isNew = !source;
        $scope.source = source || {
            id: '',
            name: '',
            url: '',
            defaultMetadataPrefix: 'oai_dc',
            defaultAggregator: '',
            defaultPrefix: 'edm',
            defaultEdmType: '',
            harvestDelay: null,
            harvestDelayUnit: 'WEEKS',
            harvestIncremental: false,
            mappingRules: [],
            ignoredSets: [],
            enabled: true
        };
        $scope.availablePrefixes = availablePrefixes || [];
        $scope.defaultMappings = defaultMappings || [];
        $scope.delayUnits = ['HOURS', 'DAYS', 'WEEKS', 'MONTHS'];
        $scope.edmTypes = ['IMAGE', 'TEXT', 'SOUND', 'VIDEO', '3D'];

        // Get mappings for a prefix
        $scope.getMappingsForPrefix = function(prefix) {
            var prefixData = $scope.defaultMappings.find(function(p) { return p.prefix === prefix; });
            return prefixData ? prefixData.mappings : [];
        };

        // Mapping rules management
        $scope.addMappingRule = function() {
            $scope.source.mappingRules.push({
                pattern: '',
                prefix: $scope.source.defaultPrefix || 'edm',
                mappingName: ''
            });
        };

        $scope.removeMappingRule = function(index) {
            $scope.source.mappingRules.splice(index, 1);
        };

        // Test regex pattern
        $scope.testPattern = function(rule) {
            var testSpec = prompt("Enter a setSpec to test (e.g., 'enb-05-bidprentje'):");
            if (testSpec) {
                try {
                    var regex = new RegExp(rule.pattern);
                    if (regex.test(testSpec)) {
                        alert("Match! '" + testSpec + "' matches pattern '" + rule.pattern + "'");
                    } else {
                        alert("No match. '" + testSpec + "' does not match pattern '" + rule.pattern + "'");
                    }
                } catch (e) {
                    alert("Invalid regex: " + e.message);
                }
            }
        };

        // Save
        $scope.save = function() {
            if (!$scope.source.name || !$scope.source.url) {
                alert("Name and URL are required.");
                return;
            }

            var promise;
            if ($scope.isNew) {
                promise = discoveryService.createSource($scope.source);
            } else {
                promise = discoveryService.updateSource($scope.source.id, $scope.source);
            }

            promise.then(function(result) {
                $modalInstance.close(result);
            }, function(error) {
                alert("Save failed: " + (error.data ? error.data.error : "Unknown error"));
            });
        };

        $scope.cancel = function() {
            $modalInstance.dismiss('cancel');
        };
    };

    SourceConfigModalCtrl.$inject = ['$scope', '$modalInstance', 'discoveryService', 'source', 'availablePrefixes', 'defaultMappings'];

    /**
     * Import Confirmation Modal Controller
     */
    var ImportConfirmModalCtrl = function ($scope, $modalInstance, selectedSets, source) {
        $scope.selectedSets = selectedSets;
        $scope.source = source;
        $scope.autoStartWorkflow = true;

        // Prepare import requests with editable fields
        $scope.importRequests = selectedSets.map(function(set) {
            return {
                sourceId: source.id,
                setSpec: set.setSpec,
                normalizedSpec: set.normalizedSpec,
                datasetName: set.title || set.setName,
                datasetDescription: set.description,
                aggregator: source.defaultAggregator,
                edmType: source.defaultEdmType,
                mappingPrefix: set.matchedMappingRule ? set.matchedMappingRule.prefix : null,
                mappingName: set.matchedMappingRule ? set.matchedMappingRule.mappingName : null,
                autoStartWorkflow: true
            };
        });

        $scope.confirm = function() {
            // Update autoStartWorkflow for all requests
            $scope.importRequests.forEach(function(req) {
                req.autoStartWorkflow = $scope.autoStartWorkflow;
            });
            $modalInstance.close($scope.importRequests);
        };

        $scope.cancel = function() {
            $modalInstance.dismiss('cancel');
        };
    };

    ImportConfirmModalCtrl.$inject = ['$scope', '$modalInstance', 'selectedSets', 'source'];

    return {
        DiscoveryCtrl: DiscoveryCtrl,
        SourceConfigModalCtrl: SourceConfigModalCtrl,
        ImportConfirmModalCtrl: ImportConfirmModalCtrl
    };
});
