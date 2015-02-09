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
            $scope.activeView = $routeParams.view || "thesauri";
            $scope.thesaurus = $routeParams.thesaurus;
        }

        function updateSearchParams() {
            $location.search({
                view: $scope.activeView,
                thesaurus: $scope.thesaurus
            });
        }

        getSearchParams();

        $scope.sourceTerm = undefined; // list selection
        $scope.sought = {}; // the field model
        $scope.mappings = {};
        $scope.show = "all";
        $scope.concepts = [];
        $scope.terms = [];
        $scope.termsVisible = [];

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
                return false;
//                var mapping = $scope.mappings[entry.sourceURI];
//                var number = parseInt(entry.count);
//                if (mapping) {
//                    mapped += number;
//                }
//                else {
//                    unmapped += number;
//                }
//                return mapping;
            }

            switch ($scope.show) {
                case "mapped":
                    $scope.termsVisible = _.filter($scope.terms, function (entry) {
                        return hasMapping(entry);
                    });
                    break;
                case "unmapped":
                    $scope.termsVisible = _.filter($scope.terms, function (entry) {
                        return !hasMapping(entry);
                    });
                    break;
                default:
                    $scope.termsVisible = _.filter($scope.terms, function (entry) {
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
        termsService.listVocabularies().then(function (data) {
            $scope.thesaurusList = _.map(data, function (t) {
                return t.skosSpec;
            });
            if ($scope.thesaurusList.length == 1) {
                $scope.selectThesaurus($scope.thesaurusList[0])
            }
        });

        termsService.getMappings($scope.spec).then(function (data) {
            console.log("What to do with these mappings", data);
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
            termsService.termVocabulary($scope.spec).then(function (terms) {
                $scope.terms = _.sortBy(terms, function(t) {
                    return - t.frequency;
                })
            });
        });

        function searchThesaurus(value) {
            if (!value || !$scope.thesaurus) return;
            $scope.scrollTo({element: '#skos-term-list', direction: 'up'});
            termsService.searchVocabulary($scope.thesaurus, value).then(function (data) {
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

        $scope.thesauriTab = function () {
            $scope.activeView = "thesauri";
            updateSearchParams();
        };

        $scope.skosTab = function () {
            if ($scope.thesaurus) {
                $scope.activeView = "skos";
                if ($scope.sourceTerm) {
                    $scope.sought.label = $scope.sourceTerm.label;
                }
                updateSearchParams();
            }
            else {
                $scope.thesauriTab();
            }
        };

        $scope.selectTerm = function (term) {
            $scope.sourceTerm = term;
            var mapping = $scope.mappings[term.uri];
            if (mapping && mapping.thesaurus != $scope.thesaurus) {
                $scope.selectThesaurus(mapping.thesaurus);
            }
            switch ($scope.activeView) {
                case "skos":
                    $scope.sought.label = term.label; // will trigger search
                    break;
//                case "record":
//                    searchRecords(entry.value);
//                    break;
            }
        };

        $scope.selectThesaurus = function (spec) {
            $scope.thesaurus = spec;
            $scope.skosTab();
            searchThesaurus($scope.sought.label);
        };

        $scope.selectSought = function (value) {
            $scope.sought.label = value;
        };

        $scope.$watch("sought", function (sought) {
            if (sought.label) searchThesaurus(sought.label);
        }, true);

        $scope.$watch("show", function () {
            filterHistogram();
        });

        $scope.$watch("histogram", function () {
            filterHistogram();
        });

        $scope.$watch("activeView", updateSearchParams());

        $scope.setMapping = function (concept) {
            var payload = {
                // todo: this sourceRUI is not yet right:
                uriA: $scope.sourceTerm.sourceURI,
                uriB: concept.uri
            };
            console.log("Mapping payload for thesaurus " + $scope.thesaurus, payload);
//            termsService.toggleMapping($scope.datasetInfo.datasetSpec, $scope.thesaurus, payload).then(function(data){
//
//            });
            alert("Sorry, not implemented yet");
//
//            if (!($scope.sourceTerm && $scope.thesaurus)) return;
//            var body = {
//                sourceURI: $scope.sourceTerm.sourceURI,
//                targetURI: concept.uri,
//                thesaurus: $scope.thesaurus,
//                attributionName: concept.attributionName,
//                prefLabel: concept.prefLabel
//            };
//            if ($scope.mappings[$scope.sourceTerm.sourceURI]) { // it already exists
//                body.remove = "yes";
//            }
//            termsService.setMapping($scope.datasetName, body).then(function (data) {
//                console.log("set mapping returns", data);
//                if (body.remove) {
//                    delete $scope.mappings[$scope.sourceTerm.sourceURI]
//                }
//                else {
//                    $scope.mappings[$scope.sourceTerm.sourceURI] = {
//                        targetURI: concept.uri,
//                        thesaurus: $scope.thesaurus,
//                        attributionName: concept.attributionName,
//                        prefLabel: concept.prefLabel,
//                        who: concept.who,
//                        when: concept.when
//                    };
//                }
//                filterHistogram();
//            });
        };
    };

    TermsCtrl.$inject = ["$rootScope", "$scope", "$location", "$routeParams", "termsService", "$timeout", "pageScroll", "user"];

    return {
        TermsCtrl: TermsCtrl
    };
});
