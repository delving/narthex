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

define(["angular"], function () {
    "use strict";

    var DatasetCtrl = function ($rootScope, $scope, $routeParams, $timeout, $location, datasetService, pageScroll, modalAlert) {
        var MAX_FOR_VOCABULARY = 12500;
        $scope.spec = $routeParams.spec;

        $scope.apiPrefix = "/narthex/api/";

        // Analysis type: 'processed' (default) or 'source'
        function updateAnalysisType() {
            $scope.analysisType = $location.search().type || 'processed';
            $scope.isSourceAnalysis = $scope.analysisType === 'source';
        }
        updateAnalysisType();

        $scope.toggleAnalysisType = function() {
            var newType = $scope.analysisType === 'source' ? 'processed' : 'source';

            // Check if target analysis exists
            if (newType === 'source' && !$scope.info.stateSourceAnalyzed) {
                modalAlert.confirm(
                    "Source Analysis Not Available",
                    "Source analysis has not been run yet. Would you like to run it now?",
                    function() {
                        datasetService.command($scope.spec, 'start source analysis').then(function() {
                            modalAlert.info("Analysis Started", "Source analysis has been started. Please wait for it to complete, then try again.");
                        });
                    }
                );
                return;
            }

            if (newType === 'processed' && !$scope.info.stateAnalyzed) {
                modalAlert.confirm(
                    "Processed Analysis Not Available",
                    "Processed analysis has not been run yet. Would you like to run it now?",
                    function() {
                        datasetService.command($scope.spec, 'analyze').then(function() {
                            modalAlert.info("Analysis Started", "Processed analysis has been started. Please wait for it to complete, then try again.");
                        });
                    }
                );
                return;
            }

            $location.search('type', newType === 'processed' ? null : newType);
        };

        // Watch for type parameter changes and reload tree
        var lastKnownType = $location.search().type;
        $scope.$watch(function() { return $location.search().type; }, function(newType, oldType) {
            // Only reload if type actually changed (not just other search params)
            if (newType !== lastKnownType) {
                lastKnownType = newType;
                updateAnalysisType();
                $scope.selectedNode = null;
                $scope.tree = null;
                fetchInfo(fetchTree);
            }
        });

        $scope.scrollTo = function (options) {
            pageScroll.scrollTo(options);
        };

        $scope.selectedNode = null;
        $scope.uniqueIdNode = null;
        $scope.recordRootNode = null;

        // Service methods that respect analysis type
        function getIndex() {
            return $scope.isSourceAnalysis
                ? datasetService.sourceIndex($scope.spec)
                : datasetService.index($scope.spec);
        }

        function getNodeStatus(path) {
            return $scope.isSourceAnalysis
                ? datasetService.sourceNodeStatus($scope.spec, path)
                : datasetService.nodeStatus($scope.spec, path);
        }

        function getSample(path, size) {
            return $scope.isSourceAnalysis
                ? datasetService.sourceSample($scope.spec, path, size)
                : datasetService.sample($scope.spec, path, size);
        }

        function getHistogram(path, size) {
            return $scope.isSourceAnalysis
                ? datasetService.sourceHistogram($scope.spec, path, size)
                : datasetService.histogram($scope.spec, path, size);
        }

        function fetchTree() {
            getIndex().then(function (tree) {

                function sortKids(node) {

                    if (!node.kids) console.log("NO KIDS:", node);

                    if (!node.kids.length) return;
                    node.kids = _.sortBy(node.kids, function (kid) {
                        return kid.tag.toLowerCase();
                    });
                    for (var index = 0; index < node.kids.length; index++) {
                        sortKids(node.kids[index]);
                    }
                }
                sortKids(tree);

                // Mark nodes with quality issues for tree view warnings
                function markQualityIssues(node) {
                    if (node.quality && node.lengths && node.lengths.length > 0) {
                        var issues = [];
                        if (node.quality.completeness < 50) {
                            issues.push('Low completeness (' + node.quality.completeness + '%)');
                        }
                        if (node.quality.emptyRate > 10) {
                            issues.push('High empty rate (' + node.quality.emptyRate + '%)');
                        }
                        if (issues.length > 0) {
                            node.hasIssues = true;
                            node.issuesSummary = issues.join(', ');
                        }
                    }
                    if (node.kids) {
                        for (var i = 0; i < node.kids.length; i++) {
                            markQualityIssues(node.kids[i]);
                        }
                    }
                }
                markQualityIssues(tree);

                function setDelimiterNodes(node) {
                    if (node.path == $scope.recordRoot) {
                        $scope.recordRootNode = node;
                    }
                    else if (node.path == $scope.uniqueId) {
                        $scope.uniqueIdNode = node;
                    }
                    else if (node.path.length > $scope.recordRoot.length) {
                        var recordContainerLength = $scope.recordRoot.lastIndexOf('/');
                        var sourcePathExtension = node.path.substring(recordContainerLength);
                        node.sourcePath = SOURCE_URI_PREFIX + sourcePathExtension;
//                      node.uri = checkSkosField(node.uri);
                    }
                    for (var index = 0; index < node.kids.length; index++) {
                        setDelimiterNodes(node.kids[index]);
                    }
                }
                if ($scope.recordRoot) setDelimiterNodes(tree);

                $scope.tree = tree;

                function selectNode(path, node) {
                    if (!path.length) {
                        $scope.selectNode(node);
                    }
                    else {
                        var tag = path.shift();
                        for (var index = 0; index < node.kids.length; index++) {
                            if (tag == node.kids[index].tag) {
                                selectNode(path, node.kids[index]);
                                return;
                            }
                        }
                    }
                }
                if ($routeParams.path) selectNode($routeParams.path.substring(1).split('/'), { tag: '', kids: [$scope.tree]});
            });
        }

        function fetchInfo(after) {
            datasetService.datasetInfo($scope.spec).then(function (info) {
//                console.log("info", info);
                $scope.info = info;
                $scope.rawAnalyzedState = !!info.stateRawAnalyzed;
                $scope.analyzedState = !!info.stateAnalyzed;
                if (info.analyzedState) {
                    $scope.recordRoot = "/rdf:Description";
                    $scope.uniqueId = "/rdf:Description/@rdf:about";
                }
                if (after) after()
            });
        }

        fetchInfo(fetchTree);

        $scope.toggleSkosifiedField = function(uri, tag, included) {
            var path = $scope.selectedNode ? $scope.selectedNode.path : $routeParams.path;
            var payload = {
                "histogramPath": path,
                "skosFieldTag": tag,
                "skosFieldUri": uri,
                "included": included
            };
            datasetService.toggleSkosifiedField($scope.spec, payload).then(function(reply) {
                fetchInfo($scope.fetchHistogram);
                console.log("toggle reply: "+ reply);
            });
        };

        $scope.goToPage = function (page) {
            $location.path("/" + page + "/" + $scope.spec);
        };

        function setActiveView(activeView) {
            $scope.activeView = activeView;
            $location.search("view", activeView);
        }

        function setActivePath(activePath) {
            $scope.activePath = activePath;
            $location.search("path", activePath);
        }

        $scope.selectNode = function (node, $event) {
            if ($event) $event.stopPropagation();
            if (node.lengths.length == 0 || node.path.length == 0) {
                node.collapsed = !node.collapsed;
                return;
            }
            $scope.selectedNode = node;
            setActivePath(node.path);
            getNodeStatus(node.path).then(function (data) {
                $scope.status = data;
                var filePath = node.path.replace(":", "_").replace("@", "_");
                $scope.apiPathUnique = $scope.apiPrefix + $scope.spec + "/unique" + filePath;
                $scope.apiPathHistogram =  $scope.apiPrefix +  $scope.spec + "/histogram" + filePath;
                $scope.sampleSize = 100;
                $scope.histogramSize = 100;
                // Use sticky activeView if set, otherwise fall back to URL param or default
                var currentView = $scope.activeView || $routeParams.view || 'histogram';
                switch (currentView) {
                    case 'sample':
                        $scope.fetchSample();
                        break;
                    case 'quality':
                        $scope.showQuality();
                        break;
                    default:
                        $scope.fetchHistogram();
                        break;
                }
            });
        };

        $scope.proposeUniqueIdNode = function (node) {
            // Gather all candidate ancestor containers (excluding document root at depth 0)
            function gatherCandidates(treeNode, targetCount) {
                var candidates = [];
                function traverse(n, depth) {
                    if (!n) return;
                    // Skip document root (depth 0) and leaf nodes (has text content)
                    if (depth > 0 && !n.lengths.length && n.count >= targetCount) {
                        candidates.push(n);
                    }
                    if (n.kids) {
                        for (var i = 0; i < n.kids.length; i++) {
                            traverse(n.kids[i], depth + 1);
                        }
                    }
                }
                traverse(treeNode, 0);
                return candidates;
            }

            var candidates = gatherCandidates($scope.tree, node.count);

            if (node.count === 1 && candidates.length > 1) {
                // Single record with multiple candidate containers - need manual selection
                $scope.uniqueIdNode = node;
                $scope.recordRootCandidates = candidates;
                $scope.showRecordRootSelector = true;
                $scope.uniqueIdChosen = false;
            } else if (candidates.length > 0) {
                // Multi-record or single candidate - auto-select first match
                $scope.recordRootNode = candidates[0];
                $scope.uniqueIdNode = node;
                $scope.uniqueIdChosen = true;
                $scope.showRecordRootSelector = false;
                $scope.recordRootCandidates = [];
            } else {
                // No valid candidates found
                $scope.uniqueIdChosen = false;
                $scope.showRecordRootSelector = false;
                $scope.recordRootCandidates = [];
            }
        };

        // Handle manual selection of record root for single-record datasets
        $scope.selectRecordRoot = function(candidate) {
            $scope.recordRootNode = candidate;
            $scope.uniqueIdChosen = true;
            $scope.showRecordRootSelector = false;
            $scope.recordRootCandidates = [];
        };

        // Check if a node is a record root candidate (for highlighting in tree)
        $scope.isRecordRootCandidate = function(node) {
            if (!$scope.showRecordRootSelector || !$scope.recordRootCandidates) return false;
            return _.some($scope.recordRootCandidates, function(c) { return c === node; });
        };

        $scope.selectPMHRecordRoot = function() {
            // Find metadata element and show its children as candidates
            var metadataNode = _.find($scope.recordRootNode.kids, function(entry) {
                return entry.tag === 'metadata';
            });

            if (!metadataNode) {
                modalAlert.warning("No Metadata Element", "Could not find metadata element in record.");
                return;
            }

            if (!metadataNode.kids || metadataNode.kids.length === 0) {
                modalAlert.warning("Empty Metadata Root", "PMH metadata root is empty. Leaving old root in place.");
                return;
            }

            // Always show candidates - let user confirm their choice
            $scope.recordRootCandidates = metadataNode.kids;
            $scope.showRecordRootSelector = true;
        };

        $scope.confirmUniqueId = function() {
            var body = {
                recordRoot: $scope.recordRootNode.path,
                uniqueId: $scope.uniqueIdNode.path
            };
            datasetService.setRecordDelimiter($scope.spec, body).then(function () {
                console.log("Record delimiter set, moving to dataset list page");
                // Add timestamp to force fresh data fetch and expand the dataset
                $location.path("/").search({dataset: $scope.spec, refresh: Date.now()});
            });
        };

        $scope.fetchSample = function () {
            var path = $scope.selectedNode ? $scope.selectedNode.path : $routeParams.path;
            getSample(path, $scope.sampleSize).then(function (data) {
                $scope.sample = data;
                $scope.histogram = undefined;
            });
            setActiveView("sample");
        };

        $scope.showQuality = function () {
            setActiveView("quality");
        };

        // Quality Summary Modal
        $scope.qualitySummary = null;
        $scope.qualitySummaryLoading = false;
        $scope.qualitySummaryError = null;
        $scope.qualityComparison = null;
        $scope.qualityComparisonLoading = false;
        $scope.qualityComparisonError = null;
        $scope.qualitySummaryTab = 'summary';  // 'summary' or 'comparison'
        // Use object to avoid child scope issues with ng-if
        $scope.qsToggles = {
            showAllProblematicFields: false,
            showFieldsInEveryRecord: false,
            showIdentifierFields: false,
            showAllFieldsWithScores: false,
            showFieldsOnlyInSource: false,
            showFieldsOnlyInProcessed: false,
            showFieldsWithChanges: false
        };

        $scope.showQualitySummary = function () {
            $scope.qualitySummaryLoading = true;
            $scope.qualitySummaryError = null;
            $scope.qualitySummary = null;
            $scope.qualitySummaryTab = 'summary';
            // Reset toggle states when opening modal
            $scope.qsToggles.showAllProblematicFields = false;
            $scope.qsToggles.showFieldsInEveryRecord = false;
            $scope.qsToggles.showIdentifierFields = false;
            $scope.qsToggles.showAllFieldsWithScores = false;
            $scope.qsToggles.showFieldsOnlyInSource = false;
            $scope.qsToggles.showFieldsOnlyInProcessed = false;
            $scope.qsToggles.showFieldsWithChanges = false;

            // Show the modal
            $('#qualitySummaryModal').modal('show');

            // Fetch the quality summary based on current analysis type
            var fetchFn = $scope.isSourceAnalysis
                ? datasetService.sourceQualitySummary
                : datasetService.qualitySummary;

            fetchFn($scope.spec).then(function (data) {
                $scope.qualitySummary = data;
                $scope.qualitySummaryLoading = false;
            }).catch(function (error) {
                $scope.qualitySummaryError = error.data ? error.data.error : 'Failed to load quality summary';
                $scope.qualitySummaryLoading = false;
            });
        };

        $scope.switchQualityTab = function (tab) {
            $scope.qualitySummaryTab = tab;
            if (tab === 'comparison' && !$scope.qualityComparison && !$scope.qualityComparisonLoading) {
                $scope.fetchQualityComparison();
            }
        };

        $scope.fetchQualityComparison = function () {
            $scope.qualityComparisonLoading = true;
            $scope.qualityComparisonError = null;
            datasetService.qualityComparison($scope.spec).then(function (data) {
                $scope.qualityComparison = data;
                $scope.qualityComparisonLoading = false;
            }).catch(function (error) {
                $scope.qualityComparisonError = error.data ? error.data.error : 'Failed to load comparison';
                $scope.qualityComparisonLoading = false;
            });
        };

        $scope.navigateToField = function (path) {
            // Close the modal
            $('#qualitySummaryModal').modal('hide');
            // Navigate to the field in the tree
            $location.search('path', path);
        };

        // Record lookup for violation samples
        $scope.recordLookupResult = null;
        $scope.recordLookupLoading = false;
        $scope.recordLookupError = null;

        $scope.lookupRecordsByValue = function (sampleValue, violationType) {
            $scope.recordLookupLoading = true;
            $scope.recordLookupError = null;
            $scope.recordLookupResult = null;
            $scope.recordLookupValue = sampleValue;
            $scope.recordLookupType = violationType || '';

            // Show the modal
            $('#recordLookupModal').modal('show');

            datasetService.recordsByValue($scope.spec, sampleValue, 100).then(function (data) {
                $scope.recordLookupResult = data;
                $scope.recordLookupLoading = false;
            }).catch(function (error) {
                $scope.recordLookupError = error.data ? error.data.error : 'Failed to lookup records';
                $scope.recordLookupLoading = false;
            });
        };

        // Generate export URL for problem records
        $scope.getExportUrl = function (format) {
            var baseUrl = '/narthex/app/dataset/' + $scope.spec + '/export-problem-records';
            var params = '?value=' + encodeURIComponent($scope.recordLookupValue || '');
            params += '&violationType=' + encodeURIComponent($scope.recordLookupType || '');
            params += '&format=' + format;
            return baseUrl + params;
        };

        function checkSkosField(uri) {
            if (!$scope.info) return false;
            if (_.isArray($scope.info.skosField)) {
                return _.indexOf($scope.info.skosField, uri) >= 0;
            }
            else {
                return $scope.info.skosField == uri;
            }
        }

        function isHistogramUniqueEnough(histogram, uniqueCount) {
            if (!histogram[0]) return false;
            var firstChunk = uniqueCount / 20; // 5%
            for (var i=0; i < firstChunk && i < histogram.length; i++) {
                if (histogram[i][0] == 1) return true;
            }
            return false;
        }

        $scope.fetchHistogram = function () {
            var path = $scope.selectedNode ? $scope.selectedNode.path : $routeParams.path;
            getHistogram(path, $scope.histogramSize).then(function (data) {
                _.forEach(data.histogram, function (entry) {
                    var percent = (100 * entry[0]) / $scope.selectedNode.count;
                    entry.push(percent);
                });
                $scope.histogram = data;
                $scope.sample = undefined;
                $scope.histogramUniqueEnough = isHistogramUniqueEnough(data.histogram, $scope.status.uniqueCount);
                $scope.histogramVocabulary = (!$scope.histogramUnique) && ($scope.status.uniqueCount < MAX_FOR_VOCABULARY);
                $scope.histogramSkosField = checkSkosField($scope.histogram.tag + "=" +$scope.histogram.uri);
            });
            setActiveView("histogram");
        };

        $scope.isMoreSample = function () {
            if (!($scope.status && $scope.status.samples)) return false;
            var which = _.indexOf($scope.status.samples, $scope.sampleSize, true);
            return which < $scope.status.samples.length - 1;
        };

        $scope.moreSample = function () {
            var which = _.indexOf($scope.status.samples, $scope.sampleSize, true);
            $scope.sampleSize = $scope.status.samples[which + 1];
            $scope.fetchSample();
        };

        $scope.isMoreHistogram = function () {
            if (!($scope.status && $scope.status.histograms)) return false;
            var which = _.indexOf($scope.status.histograms, $scope.histogramSize, true);
            return which < $scope.status.histograms.length - 1;
        };

        $scope.moreHistogram = function () {
            var which = _.indexOf($scope.status.histograms, $scope.histogramSize, true);
            $scope.histogramSize = $scope.status.histograms[which + 1];
            $scope.fetchHistogram();
        };

        $scope.getX = function () {
            return function (d) {
                return d[0];
            }
        };

        $scope.getY = function () {
            return function (d) {
                return d[1];
            }
        };

        $scope.getColor = function () {
            var lengthName = ["0", "1", "2", "3", "4", "5", "6-10", "11-15", "16-20", "21-30", "31-50", "50-100", "100-*"];
            var noOfColors = lengthName.length;
            var frequency = 4 / noOfColors;

            function toHex(c) {
                var hex = c.toString(16);
                return hex.length == 1 ? "0" + hex : hex;
            }

            function rgbToHex(r, g, b) {
                return "#" + toHex(r) + toHex(g) + toHex(b);
            }

            var colorLookup = {};
            for (var walk = 0; walk < noOfColors; ++walk) {
                var r = Math.floor(Math.sin(frequency * walk) * (127) + 128);
                var g = Math.floor(Math.sin(frequency * walk + 1) * (127) + 128);
                var b = Math.floor(Math.sin(frequency * walk + 3) * (127) + 128);
                colorLookup[lengthName[walk]] = rgbToHex(r, g, b);
            }
            return function (d) {
                return colorLookup[d.data[0]];
            };
        };

    };

    DatasetCtrl.$inject = ["$rootScope", "$scope", "$routeParams", "$timeout", "$location", "datasetService", "pageScroll", "modalAlert"];

    var TreeCtrl = function ($scope) {
        $scope.$watch('tree', function (tree) {
            if (tree) {
                $scope.node = tree;
            }
        });
    };

    TreeCtrl.$inject = ["$scope"];

    var NodeCtrl = function ($scope) {

    };

    NodeCtrl.$inject = ["$scope"];

    return {
        TreeCtrl: TreeCtrl,
        NodeCtrl: NodeCtrl,
        DatasetCtrl: DatasetCtrl
    };

});
