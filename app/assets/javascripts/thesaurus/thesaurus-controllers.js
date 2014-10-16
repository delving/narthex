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

    var ThesaurusCtrl = function ($rootScope, $scope, $location, $routeParams, thesaurusService, $timeout, pageScroll) {
        $scope.thesaurusA = $routeParams.thesaurusA;
        $scope.thesaurusB = $routeParams.thesaurusB;

        $scope.sought = "";
        $scope.soughtA = "";
        $scope.soughtB = "";
        $scope.conceptsA = [];
        $scope.conceptsB = [];

        $scope.scrollTo = function (options) {
            pageScroll.scrollTo(options);
        };

        function searchSkosA(value) {
            $scope.scrollTo({element: '#skos-term-list-a', direction: 'up'});
            thesaurusService.searchSkos($scope.thesaurusA, value).then(function (data) {
                $scope.conceptsA = data.search.results;
            });
        }

        function searchSkosB(value) {
            $scope.scrollTo({element: '#skos-term-list-b', direction: 'up'});
            thesaurusService.searchSkos($scope.thesaurusB, value).then(function (data) {
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
            if (sought) $scope.soughtA = $scope.soughtB = sought;
        });

        $scope.$watch("soughtA", function (soughtA) {
            if (soughtA) searchSkosA(soughtA);
        });

        $scope.$watch("soughtB", function (soughtB) {
            if (soughtB) searchSkosB(soughtB);
        });
    };

    ThesaurusCtrl.$inject = ["$rootScope", "$scope", "$location", "$routeParams", "thesaurusService", "$timeout", "pageScroll"];

    return {
        ThesaurusCtrl: ThesaurusCtrl
    };
});
