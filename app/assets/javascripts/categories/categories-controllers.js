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
        }
        getSearchParams();

        $scope.mappings = {};
        $scope.columnDefs = [];
        $scope.categoryGrid = {
            data: 'gridData',
            columnDefs: "columnDefs"
        };

        categoriesService.getCategoryList().then(function(categoryList) {
            $scope.categoryList = categoryList;
            var categories = categoryList.categories;
            function createArray() {
                var array = new Array(categories.length);
                for (var walk=0; walk<array.length; walk++) array[walk] = (Math.random() > 0.5)
                return array;
            }
            categoriesService.histogram($scope.fileName, $scope.path, $scope.histogramSize).then(function (data) {
                $scope.gridData = _.map(data.histogram, function (entry) {
                    var sourceUri = $rootScope.orgId + "/" + $scope.fileName + $scope.sourceUriPath + "/" + encodeURIComponent(entry[1]);
                    return {
                        term: entry[1],
                        count: entry[0],
                        sourceUri: sourceUri,
                        member: createArray()
                    }
                });
                $scope.columnDefs.push({ field: 'term', displayName: 'Term', width: 300 });
                $scope.columnDefs.push({ field: 'count', displayName: 'Count', width: 100 });
                for (var walk=0; walk<categories.length; walk++) {
                    var columnDef = {
                        field: 'category'+walk,
                        width: 40,
                        index: walk + 2,
                        displayName: walk.toString(),
                        cellTemplate:
                            '<div>'+
                            '<input type="checkbox" '+
                            'data-ng-model="row.entity.member['+walk+']" '+
                            'data-ng-click="setGridValue(row.entity, '+walk+')"'+
                            '/>'+
                            '</div>'
                    };
                    $scope.columnDefs.push(columnDef)
                }
            });
        });

        $scope.scrollTo = function (options) {
            pageScroll.scrollTo(options);
        };

        categoriesService.datasetInfo($scope.fileName).then(function (datasetInfo) {
            $scope.datasetInfo = datasetInfo;
            var recordRoot = datasetInfo.delimit.recordRoot;
            var lastSlash = recordRoot.lastIndexOf('/');
            $scope.recordContainer = recordRoot.substring(0, lastSlash);
            $scope.sourceUriPath = $scope.path.substring($scope.recordContainer.length);
        });

//        categoriesService.getCategoryMappings($scope.fileName).then(function (data) {
//            _.forEach(data.mappings, function (mapping) {
//                $scope.mappings[mapping.source] = {
//                    target: mapping.target,
//                    vocabulary: mapping.vocabulary,
//                    prefLabel: mapping.prefLabel
//                }
//            });
//            categoriesService.histogram($scope.fileName, $scope.path, $scope.histogramSize).then(function (data) {
//                $scope.histogram = _.map(data.histogram, function (count) {
//                    var sourceUri = $rootScope.orgId + "/" + $scope.fileName + $scope.sourceUriPath + "/" + encodeURIComponent(count[1]);
//                    return {
//                        value: count[1],
//                        count: count[0],
//                        sourceUri: sourceUri
//                    }
//                });
//            });
//        });

        $scope.setGridValue = function(entity, index) {
            console.log("setGridValue", entity.name, index, entity.fun[index]);
        };

        $scope.setMapping = function (category) {
            /*
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
            */
        };
    };

    CategoriesCtrl.$inject = ["$rootScope", "$scope", "$location", "$routeParams", "categoriesService", "$timeout", "pageScroll"];

    return {
        CategoriesCtrl: CategoriesCtrl
    };
});
