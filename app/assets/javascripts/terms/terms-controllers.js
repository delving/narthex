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

    var TermsCtrl = function ($scope, $location, $routeParams, dashboardService, user) {

        function getSearchParams() {
            $scope.fileName = $routeParams.fileName;
            $scope.path = $routeParams.path;
            $scope.histogramSize = parseInt($routeParams.size || "100");
            $scope.activeView = $routeParams.view || "skos";
            $scope.vocabulary = $routeParams.vocabulary;
        }
        function updateSearchParams() {
            $location.search({
                path: $scope.path,
                histogramSize: $scope.histogramSize,
                view: $scope.activeView,
                vocabulary: $scope.vocabulary
            });
        }
        getSearchParams();

        // local
        $scope.sourceValue = ""; // list selection
        $scope.sought = ""; // the field model
        $scope.mappings = {};

        $scope.createSourceUri = function(value) {
            var userPath = user.email.replace(/[@.]/g, "_");
            var prefix = userPath + "/" + $scope.fileName + $scope.path;
            return prefix + "/" + encodeURIComponent(value);
        };

        // preparations
        dashboardService.histogram($scope.fileName, $scope.path, $scope.histogramSize).then(function (data) {
            $scope.histogram = data;
        });
        dashboardService.listSkos().then(function (data) {
            $scope.vocabularyList = data.list;
        });
        dashboardService.getMappings($scope.fileName).then(function (data) {
            _.forEach(data.mappings, function(mapping) {
                $scope.mappings[mapping.source] = {
                    target: mapping.target,
                    vocabulary: mapping.vocabulary
                }
            });
        });

        function searchSkos(value) {
            if (!value || !$scope.vocabulary) return;
            dashboardService.searchSkos($scope.vocabulary, value).then(function (data) {
                $scope.conceptList = data.search;
                var mapping = $scope.mappings[$scope.sourceUri];
                if (mapping) {
                    $scope.conceptList.results = _.filter($scope.conceptList.results, function(concept) {
                        return concept.uri === mapping.target;
                    });
                }
            });
        }

        function searchRecords(value) {
            var body = {
                "path": $scope.path,
                "value": value
            };
            dashboardService.queryRecords($scope.fileName, body).then(function (data) {
                $scope.records = data;
            });
        }

        $scope.skosTab = function() {
            $scope.activeView = "skos";
            $scope.sought = $scope.sourceValue;
            updateSearchParams();
        };

        $scope.recordTab = function() {
            $scope.activeView = "record";
            searchRecords($scope.sourceValue);
            updateSearchParams();
        };

        $scope.selectSource = function (sourceValue) {
            $scope.sourceValue = sourceValue;
            $scope.sourceUri = $scope.createSourceUri($scope.sourceValue);
            var mapping = $scope.mappings[$scope.sourceUri];
            if (mapping && mapping.vocabulary != $scope.vocabulary) {
                $scope.selectVocabulary(mapping.vocabulary);
            }
            switch($scope.activeView) {
                case "skos":
                    $scope.sought = $scope.sourceValue; // will trigger searchSkos
                    break;
                case "record":
                    searchRecords(sourceValue);
                    break;
            }
        };

        $scope.selectVocabulary = function (name) {
            $scope.vocabulary = name;
            updateSearchParams();
            searchSkos($scope.sought);
        };

        $scope.selectSought = function (value) {
            $scope.sought = value;
        };

        $scope.$watch("sought", function (sought) {
            if (sought) searchSkos(sought);
        });

        $scope.$watch("activeView", updateSearchParams());

        $scope.setMapping = function (concept) {
            if (!($scope.sourceUri && $scope.vocabulary)) return;
            var body = {
                source: $scope.sourceUri,
                vocabulary: $scope.vocabulary,
                target: concept.uri
            };
            dashboardService.setMapping($scope.fileName, body).then(function (data) {
                console.log("set mapping returns", data);
                $scope.mappings[$scope.sourceUri] = {
                    target: concept.uri,
                    vocabulary: $scope.vocabulary
                }
            });
        };

        $scope.goToDataset = function () {
            $location.path("/dataset/" + $scope.fileName);
            $location.search({
                path: $scope.path,
                view: "histogram"
            });
        };
    };

    TermsCtrl.$inject = ["$scope", "$location", "$routeParams", "dashboardService", "user"];

    return {
        TermsCtrl: TermsCtrl
    };
});
