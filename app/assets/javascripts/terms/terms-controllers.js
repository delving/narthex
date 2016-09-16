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
            $location.search("view", $scope.activeView);
            $location.search("thesaurus",$scope.thesaurus);
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
                var mapping = $scope.mappings[entry.uri];
                var number = 1;
                if (entry.frequency) number = parseInt(entry.frequency);
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
            var thesaurusList = _.map(data, function (t) {
                return t.skosSpec;
            });
            $scope.thesaurusList = _.filter(thesaurusList, function (spec) {
                return spec != "categories";
            });
            if ($scope.thesaurusList.length == 1) {
                $scope.selectThesaurus($scope.thesaurusList[0])
            }
            else if ($scope.activeView != 'thesauri') {
                $scope.thesauriTab()
            }
        });

        termsService.getMappings($scope.spec).then(function (data) {
            _.forEach(data, function (mapping) {
                $scope.mappings[mapping[0]] = {
                    uri: mapping[1],
                    thesaurus: mapping[2]
                };
            });
//            console.log("mappings", $scope.mappings);
            termsService.termVocabulary($scope.spec).then(function (terms) {
                $scope.terms = _.sortBy(terms, function (t) {
                    return -t.frequency;
                });
                filterHistogram();
            });
        });

        function searchThesaurus(value) {
            if (!value || !$scope.thesaurus) return;
            $scope.scrollTo({element: '#skos-term-list', direction: 'up'});
            console.log("Searching " + $scope.thesaurus + "/" + $scope.sought.language);
            termsService.searchVocabulary($scope.thesaurus, value, $scope.sought.language).then(function (data) {
                $scope.conceptSearch = data.search;
                var mapping = $scope.sourceTerm ? $scope.mappings[$scope.sourceTerm.uri] : null;
                if (mapping) {
                    $scope.concepts = _.flatten(_.partition(data.search.results, function (concept) {
                        return concept.uri === mapping.uri;
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
            termsService.getVocabularyLanguages(spec).then(function (data) {
                // console.log("Languages", data.languages);
                $scope.languages = data.languages;
                $scope.sought.language = $scope.languages[0];
                // if lang 'nl' is available set for default;
                // todo: make this configurable at some point for international use
                if(_.indexOf($scope.languages, "nl") != -1 ){
                    $scope.sought.language = $scope.languages[_.indexOf($scope.languages, "nl")];
                }

                searchThesaurus($scope.sought.label);

                // little extra data for the frontend. used for the terms srollable div.
                // if language radios, then add more offset to align the bottom of the div with the viewport.
                $scope.termlistOffset = 285;
                if($scope.languages.length > 1 ) {
                    $scope.termlistOffset = 315;
                }
            });
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
                uriA: $scope.sourceTerm.uri,
                uriB: concept.uri
            };
            termsService.toggleMapping($scope.datasetInfo.datasetSpec, $scope.thesaurus, payload).then(function (data) {
                switch (data.action) {
                    case "added":
                        $scope.mappings[payload.uriA] = {
                            uri: payload.uriB,
                            thesaurus: $scope.thesaurus
                        };
                        break;
                    case "removed":
                        delete $scope.mappings[payload.uriA];
                        break;
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
