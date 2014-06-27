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

    var TermsCtrl = function ($scope, $location, $routeParams, dashboardService) {
        $scope.fileName = $routeParams.fileName;
        $scope.path = $routeParams.path;
        $scope.histogramSize = parseInt($routeParams.size || "100");
        $scope.selected = $routeParams.selected || "";
        $scope.vocabulary = $routeParams.vocabulary || "";
        $scope.sought = $routeParams.sought || "";

        dashboardService.histogram($scope.fileName, $scope.path, $scope.histogramSize).then(function (data) {
            $scope.histogram = data;
        });

        dashboardService.listSkos().then(function(data) {
            $scope.vocabularyList = data.list;
        });

        $scope.goToDataset = function () {
            $location.path("/dataset/" + $scope.fileName);
            $location.search({
                path: $scope.path,
                view: "histogram"
            });
        };

        function updateLocationSearch() {
            $location.search({
                path: $scope.path,
                histogramSize: $scope.histogramSize,
                selected: $scope.selected,
                vocabulary: $scope.vocabulary,
                sought: $scope.sought
            });
        }

        $scope.selectValue = function (count) {
            $scope.selected = count[1];
            $scope.sought = $scope.selected;
            updateLocationSearch();
        };

        $scope.setVocabulary = function(name) {
            $scope.vocabulary = name;
            updateLocationSearch();
        };

        $scope.setSought = function(value) {
            $scope.sought = value;
            updateLocationSearch();
        };

        function search(sought) {
            if (!sought || !$scope.vocabulary) return;
            dashboardService.searchSkos($scope.vocabulary, sought).then(function(data) {
                $scope.found = data.search;
            });
        }

        $scope.$watch("sought", function(sought, old) {
            search(sought);
        });

        $scope.$watch("vocabulary", function(vocab, old) {
            search($scope.sought);
        });
    };

    TermsCtrl.$inject = ["$scope", "$location", "$routeParams", "dashboardService"];

    return {
        TermsCtrl: TermsCtrl
    };
});
