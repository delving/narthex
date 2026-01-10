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

/**
 * Simplified controller for delimiter configuration after raw analysis.
 * Only shows tree structure, histogram, and sample - no Quality tab or source/processed toggle.
 */
define(["angular"], function () {
    "use strict";

    var DatasetDelimiterCtrl = function ($rootScope, $scope, $routeParams, $timeout, $location, datasetService, pageScroll, modalAlert) {
        $scope.spec = $routeParams.spec;

        $scope.scrollTo = function (options) {
            pageScroll.scrollTo(options);
        };

        $scope.selectedNode = null;
        $scope.uniqueIdNode = null;
        $scope.recordRootNode = null;

        // Always use base index for delimiter setting (raw analysis)
        function fetchTree() {
            datasetService.index($scope.spec).then(function (tree) {

                function sortKids(node) {
                    if (!node.kids) {
                        console.log("NO KIDS:", node);
                        return;
                    }
                    if (!node.kids.length) return;
                    node.kids = _.sortBy(node.kids, function (kid) {
                        return kid.tag.toLowerCase();
                    });
                    for (var index = 0; index < node.kids.length; index++) {
                        sortKids(node.kids[index]);
                    }
                }
                sortKids(tree);

                function setDelimiterNodes(node) {
                    if (node.path == $scope.recordRoot) {
                        $scope.recordRootNode = node;
                    }
                    else if (node.path == $scope.uniqueId) {
                        $scope.uniqueIdNode = node;
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
                $scope.info = info;
                $scope.rawAnalyzedState = !!info.stateRawAnalyzed;
                $scope.analyzedState = !!info.stateAnalyzed;
                if (after) after();
            });
        }

        fetchInfo(fetchTree);

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
            datasetService.nodeStatus($scope.spec, node.path).then(function (data) {
                $scope.status = data;
                $scope.sampleSize = 100;
                $scope.histogramSize = 100;
                var currentView = $scope.activeView || $routeParams.view || 'histogram';
                if (currentView === 'sample') {
                    $scope.fetchSample();
                } else {
                    $scope.fetchHistogram();
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
            datasetService.sample($scope.spec, path, $scope.sampleSize).then(function (data) {
                $scope.sample = data;
                $scope.histogram = undefined;
            });
            setActiveView("sample");
        };

        $scope.fetchHistogram = function () {
            var path = $scope.selectedNode ? $scope.selectedNode.path : $routeParams.path;
            datasetService.histogram($scope.spec, path, $scope.histogramSize).then(function (data) {
                _.forEach(data.histogram, function (entry) {
                    var percent = (100 * entry[0]) / $scope.selectedNode.count;
                    entry.push(percent);
                });
                $scope.histogram = data;
                $scope.sample = undefined;
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
            };
        };

        $scope.getY = function () {
            return function (d) {
                return d[1];
            };
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

    DatasetDelimiterCtrl.$inject = ["$rootScope", "$scope", "$routeParams", "$timeout", "$location", "datasetService", "pageScroll", "modalAlert"];

    var DelimiterTreeCtrl = function ($scope) {
        $scope.$watch('tree', function (tree) {
            if (tree) {
                $scope.node = tree;
            }
        });
    };

    DelimiterTreeCtrl.$inject = ["$scope"];

    var DelimiterNodeCtrl = function ($scope) {

    };

    DelimiterNodeCtrl.$inject = ["$scope"];

    return {
        DelimiterTreeCtrl: DelimiterTreeCtrl,
        DelimiterNodeCtrl: DelimiterNodeCtrl,
        DatasetDelimiterCtrl: DatasetDelimiterCtrl
    };

});
