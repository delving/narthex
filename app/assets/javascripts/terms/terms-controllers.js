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

    var TermsCtrl = function ($scope, $location, $routeParams, dashboardService, userService) {
        $scope.user = userService.getUser();
        if (!$scope.user) {
            $location.path("/");
            return;
        }
        console.log("user", $scope.user);// todo: remove
        $scope.fileName = $routeParams.fileName;
        $scope.path = $routeParams.path;
        $scope.histogramSize = parseInt($routeParams.size || "100");
        $scope.target = "";
        $scope.vocabulary = $routeParams.vocabulary || "";
        $scope.sought = $routeParams.sought || "";
        $scope.userPath = $scope.user.email.replace(/[@.]/g, "_");

        dashboardService.histogram($scope.fileName, $scope.path, $scope.histogramSize).then(function (data) {
            $scope.histogram = data;
        });

        dashboardService.listSkos().then(function (data) {
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
                source: $scope.source,
                vocabulary: $scope.vocabulary,
                sought: $scope.sought
            });
        }

        $scope.selectSource = function (value) {
            $scope.source = value;
            var prefix = $scope.userPath + "/" + $scope.fileName + $scope.path;
            $scope.sourceUri = prefix + "/" + encodeURIComponent($scope.source);
            $scope.sought = $scope.source;
            updateLocationSearch();
        };
        $scope.selectSource($routeParams.source || "");

        $scope.selectTarget = function (concept) {
            $scope.target = concept;
        };

        $scope.setVocabulary = function (name) {
            $scope.vocabulary = name;
            updateLocationSearch();
        };

        $scope.setSought = function (value) {
            $scope.sought = value;
            updateLocationSearch();
        };

        function search(sought) {
            if (!sought || !$scope.vocabulary) return;
            dashboardService.searchSkos($scope.vocabulary, sought).then(function (data) {
                $scope.found = data.search;
            });
        }

        $scope.$watch("sought", function (sought, old) {
            search(sought);
        });

        $scope.$watch("vocabulary", function (vocab, old) {
            search($scope.sought);
        });

        $scope.setMapping = function() {
            console.log("from", $scope.sourceUri);
            console.log("to", $scope.target.uri);
            var body = {
                source: $scope.sourceUri,
                target: $scope.target.uri
            };
            dashboardService.setMapping($scope.fileName, body).then(function(data) {
                console.log("set mapping returns", data);
            });
        };
    };

    TermsCtrl.$inject = ["$scope", "$location", "$routeParams", "dashboardService", "userService"];

    return {
        TermsCtrl: TermsCtrl
    };
});
