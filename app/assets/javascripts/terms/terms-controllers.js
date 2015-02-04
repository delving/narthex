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

    var TermsCtrl = function ($rootScope, $scope, $location, $routeParams, termsService, $timeout, pageScroll, user) {

        function getSearchParams() {
            $scope.spec = $routeParams.spec;
            $scope.path = $routeParams.path;
            $scope.histogramSize = parseInt($routeParams.size || "100");
            $scope.activeView = $routeParams.view || "thesaurus";
            $scope.thesaurus = $routeParams.thesaurus;
        }

        function updateSearchParams() {
            $location.search({
                path: $scope.path,
                histogramSize: $scope.histogramSize,
                view: $scope.activeView,
                thesaurus: $scope.thesaurus
            });
        }

        getSearchParams();

        $scope.sourceEntry = undefined; // list selection
        $scope.sought = ""; // the field model
        $scope.mappings = {};
        $scope.show = "all";
        $scope.concepts = [];
        $scope.histogram = [];
        $scope.histogramVisible = [];

        $scope.sourceURIPrefix = user.enrichmentPrefix + "/" + $scope.spec + $scope.path;

        $scope.scrollTo = function (options) {
            pageScroll.scrollTo(options);
        };

        termsService.datasetInfo($scope.spec).then(function (datasetInfo) {
            $scope.datasetInfo = datasetInfo;
        });

        function filterHistogram() {
            var mapped = 0;
            var unmapped = 0;

            function hasMapping(entry) {
                var mapping = $scope.mappings[entry.sourceURI];
                var number = parseInt(entry.count);
                if (mapping) {
                    mapped += number;
                }
                else {
                    unmapped += number;
                }
                return mapping;
            }

            switch ($scope.show) {
                case "mapped":
                    $scope.histogramVisible = _.filter($scope.histogram, function (entry) {
                        return hasMapping(entry);
                    });
                    break;
                case "unmapped":
                    $scope.histogramVisible = _.filter($scope.histogram, function (entry) {
                        return !hasMapping(entry);
                    });
                    break;
                default:
                    $scope.histogramVisible = _.filter($scope.histogram, function (entry) {
                        hasMapping(entry);
                        return true;
                    });
                    break;
            }
            $scope.mapped = mapped;
            $scope.unmapped = unmapped;
            $scope.all = mapped + unmapped;
        }

        // preparations
        termsService.listSkos().then(function (data) {
            $scope.thesaurusList = _.map(data,function(t) {
                return t.skosSpec;
            });
            if ($scope.thesaurusList.length == 1) {
                $scope.selectThesaurus($scope.thesaurusList[0])
            }
        });

        termsService.getMappings($scope.spec).then(function (data) {
//            _.forEach(data.mappings, function (mapping) {
//                $scope.mappings[mapping.sourceURI] = {
//                    targetURI: mapping.targetURI,
//                    thesaurus: mapping.thesaurus,
//                    attributionName: mapping.attributionName,
//                    prefLabel: mapping.prefLabel,
//                    who: mapping.who,
//                    when: mapping.when
//                }
//            });
            termsService.histogram($scope.spec, $scope.path, $scope.histogramSize).then(function (data) {
                $scope.histogram = _.map(data.histogram, function (count) {
                    var sourceURI = $scope.sourceURIPrefix + "/" + encodeURIComponent(count[1]);
                    return {
                        value: count[1],
                        count: count[0],
                        sourceURI: sourceURI
                    }
                });
            });
        });

        function searchThesaurus(value) {
            if (!value || !$scope.thesaurus) return;
            $scope.scrollTo({element: '#skos-term-list', direction: 'up'});
            termsService.searchSkos($scope.thesaurus, value).then(function (data) {
                $scope.conceptSearch = data.search;
                var mapping = $scope.mappings[$scope.sourceURI];
                if (mapping) {
                    $scope.concepts = _.flatten(_.partition(data.search.results, function (concept) {
                        return concept.uri === mapping.targetURI;
                    }));
                }
                else {
                    $scope.concepts = data.search.results;
                }
            });
        }

//        function searchRecords(value) {
//            var body = {
//                "path": $scope.path,
//                "value": value
//            };
//            termsService.queryRecords($scope.spec, body).then(function (data) {
//                $scope.records = data;
//            });
//        }

        $scope.thesauriTab = function () {
            $scope.activeView = "thesauri";
            updateSearchParams();
        };

        $scope.skosTab = function () {
            if ($scope.thesaurus) {
                $scope.activeView = "skos";
                if ($scope.sourceEntry) {
                    $scope.sought = $scope.sourceEntry.value;
                }
                updateSearchParams();
            }
            else {
                $scope.thesauriTab();
            }
        };

//        $scope.recordTab = function () {
//            $scope.activeView = "record";
//            if ($scope.sourceEntry) {
//                searchRecords($scope.sourceEntry.value);
//            }
//            updateSearchParams();
//        };

        $scope.selectSource = function (entry) {
            $scope.sourceEntry = entry;
            var mapping = $scope.mappings[entry.sourceURI];
            if (mapping && mapping.thesaurus != $scope.thesaurus) {
                $scope.selectConceptScheme(mapping.thesaurus);
            }
            switch ($scope.activeView) {
                case "skos":
                    $scope.sought = entry.value; // will trigger searchConceptScheme
                    break;
                case "record":
                    searchRecords(entry.value);
                    break;
            }
        };

        $scope.selectThesaurus = function (spec) {
            $scope.thesaurus = spec;
            $scope.skosTab();
            searchThesaurus($scope.sought);
        };

        $scope.selectSought = function (value) {
            $scope.sought = value;
        };

        $scope.$watch("sought", function (sought) {
            if (sought) searchThesaurus(sought);
        });

        $scope.$watch("show", function () {
            filterHistogram();
        });

        $scope.$watch("histogram", function () {
            filterHistogram();
        });

        $scope.$watch("activeView", updateSearchParams());

        $scope.setMapping = function (concept) {
            if (!($scope.sourceEntry && $scope.thesaurus)) return;
            var body = {
                sourceURI: $scope.sourceEntry.sourceURI,
                targetURI: concept.uri,
                thesaurus: $scope.thesaurus,
                attributionName: concept.attributionName,
                prefLabel: concept.prefLabel
            };
            if ($scope.mappings[$scope.sourceEntry.sourceURI]) { // it already exists
                body.remove = "yes";
            }
            termsService.setMapping($scope.datasetName, body).then(function (data) {
                console.log("set mapping returns", data);
                if (body.remove) {
                    delete $scope.mappings[$scope.sourceEntry.sourceURI]
                }
                else {
                    $scope.mappings[$scope.sourceEntry.sourceURI] = {
                        targetURI: concept.uri,
                        thesaurus: $scope.thesaurus,
                        attributionName: concept.attributionName,
                        prefLabel: concept.prefLabel,
                        who: concept.who,
                        when: concept.when
                    };
                }
                filterHistogram();
            });
        };
    };

    TermsCtrl.$inject = ["$rootScope", "$scope", "$location", "$routeParams", "termsService", "$timeout", "pageScroll", "user"];

    return {
        TermsCtrl: TermsCtrl
    };
});
