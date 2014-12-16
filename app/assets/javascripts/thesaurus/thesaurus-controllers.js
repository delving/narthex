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

    var ThesaurusChooseCtrl = function ($rootScope, $scope, $location, $routeParams, thesaurusService) {
        $scope.conceptSchemes = {};
        $scope.buttonEnabled = false;

        thesaurusService.listConceptSchemes().then(function (data) {
            $scope.conceptSchemeList = data.list;
        });

        $scope.goToMapping = function () {
            if (!($scope.conceptSchemes.a && $scope.conceptSchemes.b)) return;
            $location.path('/thesaurus/' + $scope.conceptSchemes.a + "/" + $scope.conceptSchemes.b);
        };

        $scope.$watch("conceptSchemes", function (schemes) {
            if (schemes.a && schemes.b) {
                $scope.buttonText = schemes.a + " <=> " + schemes.b;
                $scope.buttonEnabled = true;
            }
            else {
                $scope.buttonText = "Select two concept schemes";
                $scope.buttonEnabled = false;
            }
        }, true);
    };

    ThesaurusChooseCtrl.$inject = ["$rootScope", "$scope", "$location", "$routeParams", "thesaurusService"];

    var ThesaurusMapCtrl = function ($rootScope, $scope, $location, $routeParams, thesaurusService, $timeout, pageScroll, user) {
        var a = $routeParams.conceptSchemeA;
        var b = $routeParams.conceptSchemeB;
        $scope.conceptSchemeA = (a < b) ? a : b;
        $scope.conceptSchemeB = (a < b) ? b : a;
        $scope.show = "all";

        $scope.downloadUrl = user.narthexAPI + '/skos/' + $scope.conceptSchemeA + '/' + $scope.conceptSchemeB + '/mappings';
        $scope.mappingsAB = {};
        $scope.mappingsBA = {};

        $scope.sought = "";
        $scope.soughtA = "";
        $scope.soughtB = "";
        var fetchedConceptsA = [];
        var fetchedConceptsB = [];
        $scope.conceptsA = [];
        $scope.conceptsB = [];

        function filterConcepts() {
            switch ($scope.show) {
                case "all":
                    if ($scope.conceptA) {
                        $scope.conceptsA = [ $scope.conceptA ];
                    }
                    else {
                        $scope.conceptsA = fetchedConceptsA;
                    }
                    if ($scope.conceptB) {
                        $scope.conceptsB = [ $scope.conceptB ];
                    }
                    else {
                        $scope.conceptsB = fetchedConceptsB;
                    }
                    break;
                case "mapped":
                    if ($scope.conceptA) {
                        $scope.conceptsA = [ $scope.conceptA ];
                    }
                    else {
                        $scope.conceptsA = _.filter(fetchedConceptsA, function (c) {
                            return $scope.mappingsAB[c.uri];
                        });
                    }
                    if ($scope.conceptB) {
                        $scope.conceptsB = [ $scope.conceptB ];
                    }
                    else {
                        $scope.conceptsB = _.filter(fetchedConceptsB, function (c) {
                            return $scope.mappingsBA[c.uri];
                        });
                    }
                    break;
                case "unmapped":
                    if ($scope.conceptA) {
                        $scope.conceptsA = [ $scope.conceptA ];
                    }
                    else {
                        $scope.conceptsA = _.filter(fetchedConceptsA, function (c) {
                            return !$scope.mappingsAB[c.uri];
                        });
                    }
                    if ($scope.conceptB) {
                        $scope.conceptsB = [ $scope.conceptB ];
                    }
                    else {
                        $scope.conceptsB = _.filter(fetchedConceptsB, function (c) {
                            return !$scope.mappingsBA[c.uri];
                        });
                    }
                    break;
            }
        }

        $scope.$watch("show", function (show) {
            filterConcepts();
        });

        function fetchMappings() {
            thesaurusService.getThesaurusMappings($scope.conceptSchemeA, $scope.conceptSchemeB).then(function (data) {
                $scope.mappingsAB = {};
                $scope.mappingsBA = {};
                _.forEach(data.mappings, function (m) {
                    $scope.mappingsAB[m.uriA] = m.uriB;
                    $scope.mappingsBA[m.uriB] = m.uriA;
                });
            });
        }

        fetchMappings();

        $scope.scrollTo = function (options) {
            pageScroll.scrollTo(options);
        };

        function searchA(value) {
            $scope.scrollTo({element: '#skos-term-list-a', direction: 'up'});
            thesaurusService.searchConceptScheme($scope.conceptSchemeA, value).then(function (data) {
                fetchedConceptsA = $scope.conceptsA = data.search.results;
                $scope.conceptA = null;
            });
        }

        function searchB(value) {
            $scope.scrollTo({element: '#skos-term-list-b', direction: 'up'});
            thesaurusService.searchConceptScheme($scope.conceptSchemeB, value).then(function (data) {
                fetchedConceptsB = $scope.conceptsB = data.search.results;
                $scope.conceptB = null;
            });
        }

        $scope.selectSoughtA = function (value) {
            $scope.soughtA = value;
        };

        $scope.selectSoughtB = function (value) {
            $scope.soughtB = value;
        };

        $scope.$watch("sought", function (sought) {
            $scope.soughtA = $scope.soughtB = sought;
        });

        $scope.$watch("soughtA", function (soughtA) {
            if (!soughtA) soughtA = "-";
            searchA(soughtA);
        });

        $scope.$watch("soughtB", function (soughtB) {
            if (!soughtB) soughtB = "-";
            searchB(soughtB);
        });

        function afterSelect() {
            if ($scope.conceptA && $scope.conceptB) {
                $scope.buttonText = "'" + $scope.conceptA.prefLabel + "' <= exact match => '" + $scope.conceptB.prefLabel + "'";
                $scope.buttonEnabled = true;
            }
            else {
                if ($scope.conceptA) {
                    var uriB = $scope.mappingsAB[$scope.conceptA.uri];
                    if (uriB) {
                        $scope.selectConceptB(_.find($scope.conceptsB, function (b) {
                            return b.uri == uriB;
                        }));
                        return
                    }
                }
                else if ($scope.conceptB) {
                    var uriA = $scope.mappingsBA[$scope.conceptB.uri];
                    if (uriA) {
                        $scope.selectConceptA(_.find($scope.conceptsA, function (a) {
                            return a.uri == uriA;
                        }));
                        return
                    }
                }
                $scope.buttonText = "Select two concepts";
                $scope.buttonEnabled = false;
            }
            filterConcepts();
        }

        afterSelect();

        $scope.selectConceptA = function (c) {
            $scope.conceptA = ($scope.conceptA == c) ? null : c;
            if ($scope.conceptA) $scope.conceptsA = [ $scope.conceptA ];
            afterSelect();
        };

        $scope.selectConceptB = function (c) {
            $scope.conceptB = ($scope.conceptB == c) ? null : c;
            if ($scope.conceptB) $scope.conceptsB = [ $scope.conceptB ];
            afterSelect();
        };

        $scope.toggleMapping = function () {
            var body = {
                uriA: $scope.conceptA.uri,
                uriB: $scope.conceptB.uri
            };
            thesaurusService.setThesaurusMapping($scope.conceptSchemeA, $scope.conceptSchemeB, body).then(function (reply) {
                switch (reply.action) {
                    case "added":
                        $scope.mappingsAB[body.uriA] = body.uriB;
                        $scope.mappingsBA[body.uriB] = body.uriA;
                        break;
                    case "removed":
                        $scope.mappingsAB[body.uriA] = $scope.mappingsBA[body.uriB] = undefined;
                        break;
                }
                $scope.conceptA = $scope.conceptB = null;
                afterSelect();
            });
        };
    };

    ThesaurusMapCtrl.$inject = ["$rootScope", "$scope", "$location", "$routeParams", "thesaurusService", "$timeout", "pageScroll", "user"];

    return {
        ThesaurusChooseCtrl: ThesaurusChooseCtrl,
        ThesaurusMapCtrl: ThesaurusMapCtrl
    };
});
