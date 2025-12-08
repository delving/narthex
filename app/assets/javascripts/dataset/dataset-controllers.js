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

        $scope.scrollTo = function (options) {
            pageScroll.scrollTo(options);
        };

        $scope.selectedNode = null;
        $scope.uniqueIdNode = null;
        $scope.recordRootNode = null;

        function fetchTree() {
            datasetService.index($scope.spec).then(function (tree) {

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
            var payload = {
                "histogramPath": $routeParams.path,
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
            datasetService.nodeStatus($scope.spec, node.path).then(function (data) {
                $scope.status = data;
                var filePath = node.path.replace(":", "_").replace("@", "_");
                $scope.apiPathUnique = $scope.apiPrefix + $scope.spec + "/unique" + filePath;
                $scope.apiPathHistogram =  $scope.apiPrefix +  $scope.spec + "/histogram" + filePath;
                $scope.sampleSize = 100;
                $scope.histogramSize = 100;
                switch ($routeParams.view) {
                    case 'sample':
                        $scope.fetchSample();
                        break;
                    default:
                        $scope.fetchHistogram();
                        break;
                }
            });
        };

        $scope.proposeUniqueIdNode = function (node) {
            function selectFirstEmptyWithCount(node, count) {
                if (!node) return undefined;
                if (!node.lengths.length && node.count >= count) {
                    return node;
                }
                else for (var index = 0; index < node.kids.length; index++) {
                    var emptyWithCount = selectFirstEmptyWithCount(node.kids[index], count);
                    if (emptyWithCount) return emptyWithCount;
                }
                return undefined;
            }
            var recordRootNode = selectFirstEmptyWithCount($scope.tree, node.count);
            $scope.uniqueIdChosen = !!recordRootNode;
            if (recordRootNode) {
                $scope.recordRootNode = recordRootNode;
                $scope.uniqueIdNode = node;
            }
        };

        $scope.selectPMHRecordRoot = function() {
            $scope.recordRootNode.kids.forEach(function(entry) {
                if (entry.tag === 'metadata') {
                    if (entry.length != 0) {
                        $scope.recordRootNode = entry.kids[0];
                    } else {
                        modalAlert.warning("Empty Metadata Root", "PMH metadata root is empty. Leaving old root in place");
                    }
                };
            });
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
            datasetService.sample($scope.spec, $routeParams.path, $scope.sampleSize).then(function (data) {
                $scope.sample = data;
                $scope.histogram = undefined;
            });
            setActiveView("sample");
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
            datasetService.histogram($scope.spec, $routeParams.path, $scope.histogramSize).then(function (data) {
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
