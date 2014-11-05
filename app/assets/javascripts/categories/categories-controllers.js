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

        categoriesService.datasetInfo($scope.fileName).then(function (datasetInfo) {
            $scope.datasetInfo = datasetInfo;
            var recordRoot = datasetInfo.delimit.recordRoot;
            var lastSlash = recordRoot.lastIndexOf('/');
            $scope.recordContainer = recordRoot.substring(0, lastSlash);
            var sourceUriPath = $scope.path.substring($scope.recordContainer.length);

            categoriesService.getCategoryList().then(function (categoryList) {
                $scope.categories = categoryList.categories;
                $scope.columnDefs.push({ field: 'term', displayName: 'Term', width: 460 });
                $scope.columnDefs.push({ field: 'count', displayName: 'Count', width: 100, cellClass: "category-count-cell" });
                for (var walk = 0; walk < $scope.categories.length; walk++) {
                    var code = $scope.categories[walk].code;
                    var codeQuoted = "'"+code+"'";
                    $scope.columnDefs.push({
                        field: 'category' + walk,
                        index: walk + 2,
                        headerCellTemplate:
                            '<div class="category-header" data-ng-click="clickCategoryHeader($index)">' +
                            '  <span class="category-header-text">' + code.toUpperCase() + '</span>' +
                            '</div>',
                        cellTemplate:
                            '<div class="category-cell">' +
                            '  <input type="checkbox" data-ng-model="row.entity.memberOf[' + codeQuoted + ']" ' +
                            '         data-ng-click="setGridValue(row.entity, ' + codeQuoted + ')"/>' +
                            '</div>'
                    })
                }
                categoriesService.histogram($scope.fileName, $scope.path, $scope.histogramSize).then(function (histogramData) {
                    $scope.gridData = _.map(histogramData.histogram, function (entry) {
                        var sourceUri = $rootScope.orgId + "/" + $scope.fileName + sourceUriPath + "/" + encodeURIComponent(entry[1]);
                        return {
                            term: entry[1],
                            count: entry[0],
                            sourceUri: sourceUri,
                            memberOf: {}
                        }
                    });

                    categoriesService.getCategoryMappings($scope.fileName).then(function(mappingsData) {
                        var mappingLookup = {};
                        _.forEach(mappingsData.mappings, function(mapping) {
                            mappingLookup[mapping.source] = mapping.categories;
                        });
                        _.forEach($scope.gridData, function (row) {
                            var categories = mappingLookup[row.sourceUri];
                            _.forEach(categories, function (category) {
                                row.memberOf[category] = true;
                            });
                        });
                    });
                });
            });
        });

        $scope.clickCategoryHeader = function(index) {
            console.log("CLICK header", index);
        };

        $scope.setGridValue = function(entity, code) {
            var body = {
                source: entity.sourceUri,
                category: code,
                member: entity.memberOf[code]
            };
            categoriesService.setCategoryMapping($scope.fileName, body).then(function (data) {
//                console.log("set category mapping returns", data);
            });
        };

//        $scope.scrollTo = function (options) {
//            pageScroll.scrollTo(options);
//        };
    };

    CategoriesCtrl.$inject = ["$rootScope", "$scope", "$location", "$routeParams", "categoriesService", "$timeout", "pageScroll"];

    return {
        CategoriesCtrl: CategoriesCtrl
    };
});
