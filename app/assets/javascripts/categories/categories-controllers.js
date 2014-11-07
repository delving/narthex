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

    var CategorySetCtrl = function ($rootScope, $scope, $location, $routeParams, categoriesService, $timeout, pageScroll) {

        function getSearchParams() {
            $scope.fileName = $routeParams.fileName;
            $scope.path = $routeParams.path;
            $scope.histogramSize = parseInt($routeParams.size || "100");
        }

        getSearchParams();

        $scope.categoryHelp = {
            code: "Details",
            details: "Click on a column header for details"
        };
        $scope.mappings = {};
        $scope.columnDefs = [];
        $scope.categoryGrid = {
            data: 'gridData',
            columnDefs: "columnDefs",
            enableRowSelection: false
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
                    var codeQuoted = "'" + code + "'";
                    var busyClassQuoted = "'category-cell-busy'";
                    $scope.columnDefs.push({
                        field: 'category' + walk,
                        index: walk + 2,
                        headerCellTemplate: '<div class="category-header" data-ng-click="clickCategoryHeader($index - 2)">' +
                            '  <span class="category-header-text">' + code.toUpperCase() + '</span>' +
                            '</div>',
                        cellTemplate: '<div class="category-cell" data-ng-class="{ ' + busyClassQuoted + ': (row.entity.busyCode == ' + codeQuoted + ') }">' +
                            '  <input type="checkbox" class="category-checkbox" data-ng-model="row.entity.memberOf[' + codeQuoted + ']" ' +
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

                    categoriesService.getCategoryMappings($scope.fileName).then(function (mappingsData) {
                        var mappingLookup = {};
                        _.forEach(mappingsData.mappings, function (mapping) {
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

        $scope.clickCategoryHeader = function (index) {
            $scope.categoryHelp = $scope.categories[index];
//            console.log("CLICK header", $scope.categories[index]);
        };

        $scope.setGridValue = function (entity, code) {
            var body = {
                source: entity.sourceUri,
                category: code,
                member: entity.memberOf[code]
            };
            entity.busyCode = code;
            categoriesService.setCategoryMapping($scope.fileName, body).then(function (data) {
                delete entity.busyCode;
            });
        };

//        $scope.scrollTo = function (options) {
//            pageScroll.scrollTo(options);
//        };
    };

    CategorySetCtrl.$inject = ["$rootScope", "$scope", "$location", "$routeParams", "categoriesService", "$timeout", "pageScroll"];

    var CategoryMonitorCtrl = function ($rootScope, $scope, $location, $routeParams, categoriesService, $timeout, pageScroll) {

        $scope.datasetBusy = false;

        function fetchDatasetList() {
            categoriesService.list().then(function (files) {
                _.forEach($scope.files, cancelChecker);
                _.forEach(files, decorateFile);
                $scope.files = _.filter(files, function (file) {
                    return file.info.categories && file.info.categories.included == 'true';
                });
                $scope.datasetBusy = false;
                _.forEach(files, function(file) {
                    if (file.progress) {
                        checkProgress(file);
                        $scope.datasetBusy = true;
                    }
                });
            });
        }
        fetchDatasetList();

        function decorateFile(file) {
            var info = file.info;
            delete(file.error);
            if (info.progress) {
                if (info.progress.type != 'progress-idle') {
                    file.progress = info.progress;
                    file.progress.message = createProgressMessage(info.progress);
                }
                else {
                    if (info.progress.state == 'state-error') {
                        file.error = info.progress.error;
                    }
                    delete(file.progress);
                }
            }
            var parts = file.name.split(/__/);
            file.identity = {
                datasetName: parts[0],
                prefix: parts[1]
            };
            if (info.status) {
                file.state = info.status.state;
            }
            if (info.origin) {
                file.origin = info.origin.type;
            }
            if (info.delimit && info.delimit.recordCount > 0) {
                file.identity.recordCount = info.delimit.recordCount;
            }
            if (info.metadata) {
                file.identity.name = info.metadata.name;
                file.identity.dataProvider = info.metadata.dataProvider;
            }
        }

        function cancelChecker(file) {
            if (file.checker) {
                $timeout.cancel(file.checker);
                delete(file.checker);
            }
        }

        function checkProgress(file) {
            categoriesService.datasetInfo(file.name).then(function (info) {
                file.info = info;
                decorateFile(file);
                if (!file.progress) return;
                file.checker = $timeout(
                    function () {
                        checkProgress(file)
                    },
                    1000
                );
            }, function (problem) {
                if (problem.status == 404) {
                    alert("Processing problem with " + file.name);
                    fetchDatasetList()
                }
                else {
                    alert("Network problem " + problem.status);
                }
            })
        }

        $scope.gatherCategoryCounts = function () {
            categoriesService.gatherCategoryCounts().then(function (files) {
                fetchDatasetList();
            });
        };
    };

    CategoryMonitorCtrl.$inject = ["$rootScope", "$scope", "$location", "$routeParams", "categoriesService", "$timeout", "pageScroll"];

    return {
        CategorySetCtrl: CategorySetCtrl,
        CategoryMonitorCtrl: CategoryMonitorCtrl
    };
});
