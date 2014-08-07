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

    var TermsCtrl = function ($scope, $location, $routeParams, dashboardService, $timeout, pageScroll ) {

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
        $scope.show = "all";
        $scope.concepts = [];
        $scope.histogram = [];
        $scope.histogramVisible = [];

        $scope.scrollTo = function(options){
            pageScroll.scrollTo(options);
        };

        $scope.createSourceUri = function(value) {
            // todo: start with orgId, get it from... well it's in index.html
            var prefix = $scope.fileName + $scope.path;
            return prefix + "/" + encodeURIComponent(value);
        };

        function filterHistogram() {
            var mapped = 0;
            var unmapped = 0;

            function hasMapping(count) {
                var mapping = $scope.mappings[$scope.createSourceUri(count[1])];
                var number = parseInt(count[0]);
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
                    $scope.histogramVisible = _.filter($scope.histogram, function(count){
                        return hasMapping(count);
                    });
                    break;
                case "unmapped":
                    $scope.histogramVisible = _.filter($scope.histogram, function(count){
                        return !hasMapping(count);
                    });
                    break;
                default:
                    $scope.histogramVisible = _.filter($scope.histogram, function(count){
                        hasMapping(count);
                        return true;
                    });
                    break;
            }
            $scope.mapped = mapped;
            $scope.unmapped = unmapped;
            $scope.all = mapped + unmapped;
        }

        // preparations
        dashboardService.listSkos().then(function (data) {
            $scope.vocabularyList = data.list;
            if ($scope.vocabularyList.length == 1) {
                $scope.selectVocabulary($scope.vocabularyList[0])
            }
        });
        dashboardService.getMappings($scope.fileName).then(function (data) {
            _.forEach(data.mappings, function(mapping) {
                $scope.mappings[mapping.source] = {
                    target: mapping.target,
                    vocabulary: mapping.vocabulary
                }
            });
            dashboardService.histogram($scope.fileName, $scope.path, $scope.histogramSize).then(function (data) {
                $scope.histogram = data.histogram;
            });
        });

        function searchSkos(value) {
            if (!value || !$scope.vocabulary) return;
            // todo: should somehow scroll to the top of the concept list
            dashboardService.searchSkos($scope.vocabulary, value).then(function (data) {
                $scope.conceptSearch = data.search;
                var mapping = $scope.mappings[$scope.sourceUri];
                if (mapping) {
                    $scope.concepts = _.flatten(_.partition(data.search.results, function(concept) {
                        return concept.uri === mapping.target;
                    }));
                }
                else {
                    $scope.concepts = data.search.results;
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

        $scope.$watch("show", function () {
            filterHistogram();
        });

        $scope.$watch("histogram", function () {
            filterHistogram();
        });

        $scope.$watch("activeView", updateSearchParams());

        $scope.setMapping = function (concept) {
            if (!($scope.sourceUri && $scope.vocabulary)) return;
            var body = {
                source: $scope.sourceUri,
                target: concept.uri,
                vocabulary: $scope.vocabulary,
                prefLabel: concept.prefLabel
            };
            dashboardService.setMapping($scope.fileName, body).then(function (data) {
                console.log("set mapping returns", data);
                $scope.mappings[$scope.sourceUri] = {
                    target: concept.uri,
                    vocabulary: $scope.vocabulary,
                    prefLabel: concept.prefLabel
                };
                filterHistogram();
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

    TermsCtrl.$inject = ["$scope", "$location", "$routeParams", "dashboardService", "$timeout", "pageScroll"];

    return {
        TermsCtrl: TermsCtrl
    };
});
