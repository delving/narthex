//===========================================================================
//    Copyright 2024 Delving B.V.
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

    var DefaultMappingsListCtrl = function ($scope, $rootScope, $location, $modal, defaultMappingsService, datasetListService) {

        $scope.prefixes = [];
        $scope.datasets = [];
        $scope.availablePrefixes = [];
        $scope.selectedFile = null;
        $scope.uploadNotes = '';
        $scope.newMappingName = '';
        $scope.newMappingPrefix = '';

        // Two-level expansion state: prefix -> expanded, and prefix/name -> expanded
        $scope.expandedPrefixes = {};
        $scope.expandedMappings = {};

        function loadMappings() {
            defaultMappingsService.listDefaultMappings().then(function (data) {
                $scope.prefixes = data.prefixes || [];
                $scope.availablePrefixes = data.availablePrefixes || [];
            });
        }

        function loadDatasets() {
            datasetListService.listDatasetsLight().then(function (data) {
                $scope.datasets = data.datasets || data || [];
            });
        }

        // =============== Prefix Level Expansion ===============

        $scope.togglePrefixExpand = function (prefix) {
            if ($scope.expandedPrefixes[prefix]) {
                delete $scope.expandedPrefixes[prefix];
            } else {
                $scope.expandedPrefixes[prefix] = true;
            }
        };

        $scope.isPrefixExpanded = function (prefix) {
            return $scope.expandedPrefixes[prefix];
        };

        // =============== Named Mapping Level Expansion ===============

        $scope.toggleMappingExpand = function (prefix, name) {
            var key = prefix + '/' + name;
            if ($scope.expandedMappings[key]) {
                delete $scope.expandedMappings[key];
            } else {
                $scope.expandedMappings[key] = true;
                loadMappingVersions(prefix, name);
            }
        };

        $scope.isMappingExpanded = function (prefix, name) {
            return $scope.expandedMappings[prefix + '/' + name];
        };

        function loadMappingVersions(prefix, name) {
            defaultMappingsService.getNamedMappingInfo(prefix, name).then(function (data) {
                // Find and update the mapping in the prefixes list
                for (var i = 0; i < $scope.prefixes.length; i++) {
                    if ($scope.prefixes[i].prefix === prefix) {
                        for (var j = 0; j < $scope.prefixes[i].mappings.length; j++) {
                            if ($scope.prefixes[i].mappings[j].name === name) {
                                $scope.prefixes[i].mappings[j].versions = data.versions || [];
                                $scope.prefixes[i].mappings[j].currentVersion = data.currentVersion;
                                break;
                            }
                        }
                        break;
                    }
                }
            });
        }

        // =============== Utility Functions ===============

        $scope.formatTimestamp = function (timestamp) {
            if (!timestamp) return '';
            var date = new Date(timestamp);
            return date.toLocaleString();
        };

        $scope.getTotalVersionCount = function (prefixData) {
            var count = 0;
            if (prefixData.mappings) {
                for (var i = 0; i < prefixData.mappings.length; i++) {
                    count += prefixData.mappings[i].versionCount || 0;
                }
            }
            return count;
        };

        // =============== Create New Named Mapping ===============

        $scope.createNewMapping = function (prefix) {
            if (!$scope.newMappingName || !$scope.newMappingName.trim()) {
                alert('Please enter a mapping name');
                return;
            }

            defaultMappingsService.createNamedMapping(prefix, $scope.newMappingName.trim()).then(function (data) {
                $scope.newMappingName = '';
                loadMappings();
                // Expand the prefix to show the new mapping
                $scope.expandedPrefixes[prefix] = true;
            });
        };

        $scope.createFirstMapping = function () {
            if (!$scope.newMappingPrefix) {
                alert('Please select a prefix');
                return;
            }
            if (!$scope.newMappingName || !$scope.newMappingName.trim()) {
                alert('Please enter a mapping name');
                return;
            }
            if (!$scope.selectedFile) {
                alert('Please select a file to upload');
                return;
            }

            var prefix = $scope.newMappingPrefix;
            var displayName = $scope.newMappingName.trim();

            // First create the named mapping, then upload the file
            defaultMappingsService.createNamedMapping(prefix, displayName).then(function (data) {
                var name = data.name;  // The slug generated from displayName
                return defaultMappingsService.uploadDefaultMapping(prefix, name, $scope.selectedFile, $scope.uploadNotes);
            }).then(function () {
                $scope.newMappingPrefix = '';
                $scope.newMappingName = '';
                $scope.selectedFile = null;
                $scope.uploadNotes = '';
                loadMappings();
            });
        };

        // =============== Upload to Existing Mapping ===============

        $scope.uploadMapping = function (prefix, name) {
            if (!$scope.selectedFile) {
                alert('Please select a file to upload');
                return;
            }

            defaultMappingsService.uploadDefaultMapping(prefix, name, $scope.selectedFile, $scope.uploadNotes).then(function (data) {
                $scope.selectedFile = null;
                $scope.uploadNotes = '';
                loadMappings();
                loadMappingVersions(prefix, name);
            });
        };

        // =============== Copy from Dataset ===============

        $scope.copyFromDataset = function (prefix, name, spec) {
            if (!spec) {
                alert('Please select a dataset');
                return;
            }

            defaultMappingsService.copyMappingFromDataset(prefix, name, spec, null).then(function (data) {
                loadMappings();
                loadMappingVersions(prefix, name);
            });
        };

        // =============== Version Management ===============

        $scope.setCurrentVersion = function (prefix, name, hash) {
            defaultMappingsService.setCurrentDefaultMapping(prefix, name, hash).then(function (data) {
                loadMappingVersions(prefix, name);
            });
        };

        $scope.deleteVersion = function (prefix, name, hash) {
            if (!confirm('Are you sure you want to delete this mapping version?')) {
                return;
            }

            defaultMappingsService.deleteDefaultMappingVersion(prefix, name, hash).then(function (data) {
                loadMappingVersions(prefix, name);
            });
        };

        // =============== Preview Mapping ===============

        $scope.previewMapping = function (prefix, name, version) {
            defaultMappingsService.getDefaultMappingXml(prefix, name, version).then(function (data) {
                var modalInstance = $modal.open({
                    templateUrl: '/narthex/assets/templates/xml-preview-modal.html',
                    controller: 'XmlPreviewModalCtrl',
                    size: 'lg',
                    resolve: {
                        xmlContent: function () {
                            return data;
                        },
                        title: function () {
                            return 'Mapping: ' + prefix.toUpperCase() + ' - ' + name + ' (' + version + ')';
                        }
                    }
                });
            });
        };

        // =============== File Selection ===============

        $scope.onFileSelect = function (files) {
            if (files && files.length > 0) {
                $scope.selectedFile = files[0];
                $scope.$apply();  // Ensure the scope is updated
            }
        };

        // Initialize
        loadMappings();
        loadDatasets();
    };

    DefaultMappingsListCtrl.$inject = [
        "$scope", "$rootScope", "$location", "$modal", "defaultMappingsService", "datasetListService"
    ];

    // XML Preview Modal Controller
    var XmlPreviewModalCtrl = function ($scope, $modalInstance, $timeout, xmlContent, title) {
        $scope.xmlContent = xmlContent;
        $scope.title = title;
        $scope.formattedXml = formatXml(xmlContent);

        $scope.close = function () {
            $modalInstance.dismiss('cancel');
        };

        // Trigger Prism highlighting after the view is rendered
        $timeout(function() {
            if (window.Prism) {
                Prism.highlightAll();
            }
        }, 100);

        function formatXml(xml) {
            if (!xml) return '';
            // Use browser's XML parser for proper formatting
            try {
                var parser = new DOMParser();
                var xmlDoc = parser.parseFromString(xml, 'text/xml');
                var serializer = new XMLSerializer();
                var formatted = serializer.serializeToString(xmlDoc);
                // Add newlines and indentation
                var indent = 0;
                var result = '';
                formatted.split(/>\s*</).forEach(function(node, index) {
                    if (index > 0) node = '<' + node;
                    if (index < formatted.split(/>\s*</).length - 1) node = node + '>';

                    if (node.match(/^<\/\w/)) indent--;
                    result += '  '.repeat(Math.max(0, indent)) + node.trim() + '\n';
                    if (node.match(/^<\w[^>]*[^\/]>$/)) indent++;
                });
                return result.trim();
            } catch (e) {
                // If parsing fails, return original
                return xml;
            }
        }
    };

    XmlPreviewModalCtrl.$inject = ["$scope", "$modalInstance", "$timeout", "xmlContent", "title"];

    return {
        DefaultMappingsListCtrl: DefaultMappingsListCtrl,
        XmlPreviewModalCtrl: XmlPreviewModalCtrl
    };
});
