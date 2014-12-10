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

        $scope.goToMapping = function() {
            if (!($scope.conceptSchemes.a && $scope.conceptSchemes.b)) return;
            $location.path('/thesaurus/'+$scope.conceptSchemes.a+"/"+$scope.conceptSchemes.b);
        };

        $scope.$watch("conceptSchemes", function(schemes) {
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

    var ThesaurusMapCtrl = function ($rootScope, $scope, $location, $routeParams, thesaurusService, $timeout, pageScroll) {
        $scope.conceptSchemeA = $routeParams.conceptSchemeA;
        $scope.conceptSchemeB = $routeParams.conceptSchemeB;

        $scope.sought = "";
        $scope.soughtA = "";
        $scope.soughtB = "";
        $scope.conceptsA = [];
        $scope.conceptsB = [];

        $scope.scrollTo = function (options) {
            pageScroll.scrollTo(options);
        };

        function searchA(value) {
            $scope.scrollTo({element: '#skos-term-list-a', direction: 'up'});
            thesaurusService.searchConceptScheme($scope.conceptSchemeA, value).then(function (data) {
                console.log("AA");
                $scope.conceptsA = data.search.results;
            });
        }

        function searchB(value) {
            $scope.scrollTo({element: '#skos-term-list-b', direction: 'up'});
            thesaurusService.searchConceptScheme($scope.conceptSchemeB, value).then(function (data) {
                $scope.conceptsB = data.search.results;
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

        $scope.buttonText = "Select two concepts";
        $scope.buttonEnabled = false;

//        $scope.$watch("conceptSchemes", function(schemes) {
//            if (schemes.a && schemes.b) {
//                $scope.buttonText = schemes.a + " <=> " + schemes.b;
//                $scope.buttonEnabled = true;
//            }
//            else {
//                $scope.buttonText = "Select two concept schemes";
//                $scope.buttonEnabled = false;
//            }
//        }, true);

    };

    ThesaurusMapCtrl.$inject = ["$rootScope", "$scope", "$location", "$routeParams", "thesaurusService", "$timeout", "pageScroll"];

    return {
        ThesaurusChooseCtrl: ThesaurusChooseCtrl,
        ThesaurusMapCtrl: ThesaurusMapCtrl
    };
});
