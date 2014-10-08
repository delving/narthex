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

    var CategoriesCtrl = function ($rootScope, $scope, $location, $routeParams, categoriesService, $timeout, pageScroll) {

        function getSearchParams() {
            $scope.fileName = $routeParams.fileName;
            $scope.path = $routeParams.path;
            $scope.histogramSize = parseInt($routeParams.size || "100");
            $scope.vocabulary = $routeParams.vocabulary;
        }

        function updateSearchParams() {
            $location.search({
                path: $scope.path,
                histogramSize: $scope.histogramSize,
                vocabulary: $scope.vocabulary
            });
        }

        getSearchParams();

        // local
        $scope.sourceEntry = undefined; // list selection
        $scope.mappings = {};
        $scope.show = "all";
        $scope.concepts = [];
        $scope.histogram = [];
        $scope.histogramVisible = [];

        $scope.scrollTo = function (options) {
            pageScroll.scrollTo(options);
        };

        categoriesService.datasetInfo($scope.fileName).then(function (datasetInfo) {
            $scope.datasetInfo = datasetInfo;
            var recordRoot = datasetInfo.delimit.recordRoot;
            var lastSlash = recordRoot.lastIndexOf('/');
            $scope.recordContainer = recordRoot.substring(0, lastSlash);
            $scope.sourceUriPath = $scope.path.substring($scope.recordContainer.length);
            var state = datasetInfo.status.state;
            $scope.datasetRecordsSaved = (state == 'state-published' || state == 'state-saved');
        });

        function filterHistogram() {
            var mapped = 0;
            var unmapped = 0;

            function hasMapping(entry) {
                var mapping = $scope.mappings[entry.sourceUri];
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
        categoriesService.getCategoryMappings($scope.fileName).then(function (data) {
            _.forEach(data.mappings, function (mapping) {
                $scope.mappings[mapping.source] = {
                    target: mapping.target,
                    vocabulary: mapping.vocabulary,
                    prefLabel: mapping.prefLabel
                }
            });
            categoriesService.histogram($scope.fileName, $scope.path, $scope.histogramSize).then(function (data) {
                $scope.histogram = _.map(data.histogram, function (count) {
                    var sourceUri = $rootScope.orgId + "/" + $scope.fileName + $scope.sourceUriPath + "/" + encodeURIComponent(count[1]);
                    return {
                        value: count[1],
                        count: count[0],
                        sourceUri: sourceUri
                    }
                });
            });
        });

        $scope.selectSource = function (entry) {
            $scope.sourceEntry = entry;
            var mapping = $scope.mappings[entry.sourceUri];
            // todo: more!
        };

        $scope.$watch("show", function () {
            filterHistogram();
        });

        $scope.$watch("histogram", function () {
            filterHistogram();
        });

        $scope.setMapping = function (category) {
            if (!$scope.sourceEntry) return;
            var body = {
                source: $scope.sourceEntry.sourceUri,
                target: category
            };
            if ($scope.mappings[$scope.sourceEntry.sourceUri]) { // it already exists
                body.remove = "yes";
            }
            categoriesService.setCategoryMapping($scope.fileName, body).then(function (data) {
                console.log("set mapping returns", data);
                if (body.remove) {
                    delete $scope.mappings[$scope.sourceEntry.sourceUri]
                }
                else {
                    $scope.mappings[$scope.sourceEntry.sourceUri] = {
                        target: category
                    };
                }
                filterHistogram();
            });
        };
    };

    CategoriesCtrl.$inject = ["$rootScope", "$scope", "$location", "$routeParams", "categoriesService", "$timeout", "pageScroll"];

    return {
        CategoriesCtrl: CategoriesCtrl
    };
});
