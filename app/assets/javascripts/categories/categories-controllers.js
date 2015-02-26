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
            $scope.spec = $routeParams.spec;
            $scope.path = $routeParams.path;
            $scope.histogramSize = parseInt($routeParams.size || "100");
        }

        getSearchParams();

        $scope.categoryHelp = {
            code: "",
            details: ""
        };
        $scope.mappings = {};
        $scope.gridData = [];
        $scope.categories = [];
        $scope.visible = {};

        var sourceURIPath = "/SOURCEURIPATH";

        function columnDefinitionsFromCategories() {
            $scope.columnDefs = [];
            $scope.columnDefs.push({ field: 'term', displayName: 'Term', width: 460 });
            $scope.columnDefs.push({ field: 'count', displayName: 'Count', width: 100, maxWidth: 100, cellClass: "category-count-cell" });
            var numberVisible = 0;
            for (var walk = 0; walk < $scope.categories.length; walk++) {
                var code = $scope.categories[walk].code;
                if (!$scope.visible[code]) continue;
                var targetUri = $scope.categories[walk].uri;
                numberVisible++;
                var codeQuoted = "'" + code + "'";
                var targetUriQuoted = "'" + targetUri + "'";
                var busyClassQuoted = "'category-cell-busy'";
                $scope.columnDefs.push({
                    field: 'category' + walk,
                    index: walk + 2,
                    headerCellTemplate: '<div class="category-header">' +
                        '  <span class="category-header-text">' + code.toUpperCase() + '</span>' +
                        '</div>',
                    cellTemplate: '<div class="category-cell" data-ng-class="{ ' + busyClassQuoted + ': (row.entity.busyCode == ' + codeQuoted + ') }">' +
                        '  <input type="checkbox" class="category-checkbox" data-ng-model="row.entity.memberOf[' + codeQuoted + ']" ' +
                        '         data-ng-click="setGridValue(row.entity, ' + targetUriQuoted + ', ' + codeQuoted + ')"/>' +
                        '</div>'
                })
            }
            if (!numberVisible) $scope.columnDefs.push({ field: 'boo', displayName: '', width: 1 });
        }

        categoriesService.getCategoryList().then(function (categoryList) {
            $scope.categories = categoryList.categories;
            if (!$scope.categories) {
                alert("No categories!");
                return;
            }
            columnDefinitionsFromCategories();
            $scope.showCategoryExplain(0);

            categoriesService.termVocabulary($scope.spec).then(function (terms) {
                $scope.terms = _.sortBy(terms, function (t) {
                    return -t.frequency;
                });
                $scope.gridData = _.map($scope.terms, function (entry) {
                    return {
                        term: entry.label,
                        count: entry.frequency,
                        sourceURI: entry.uri,
                        memberOf: {}
                    }
                });
                categoriesService.getMappings($scope.spec).then(function (data) {
                    var mappings = {};
                    _.forEach(data, function (mapping) {
                        var existing = mappings[mapping[0]];
                        if (!existing) existing = mappings[mapping[0]] = [];
                        var category = _.find($scope.categories, function (cat) {
                            return cat.uri == mapping[1];
                        });
                        if (category) {
                            existing.push(category.code);
                        }
                        else {
                            console.log("Couldn't find category: " + mapping[1]);
                        }
                        if (mapping[2] != "categories") console.log("Not a category mapping?: " + mapping[2]);
                    });
                    $scope.mappings = mappings;
                    _.forEach($scope.gridData, function (row) {
                        var categories = $scope.mappings[row.sourceURI];
                        _.forEach(categories, function (category) {
                            row.memberOf[category] = true;
                        });
                    });
                });
            });
        });

        $scope.categoryGrid = {
            data: 'gridData',
            columnDefs: "columnDefs",
            afterSelectionChange: function (rowItem) {
                var visible = {};
                _.forEach($scope.categoryGrid.$gridScope.selectedItems, function (selectedItem) {
                    _.forEach($scope.categories, function (category) {
                        if (selectedItem.memberOf[category.code]) visible[category.code] = true;
                    });
                });
                $scope.visible = visible;
                columnDefinitionsFromCategories();
            }
        };

        $scope.setCategory = function (code, visible) {
            if (visible) {
                $scope.visible[code] = visible;
            }
            else {
                $scope.visible[code] = !$scope.visible[code];
            }
            columnDefinitionsFromCategories();
        };

        $scope.showCategoryExplain = function (index) {
            $scope.categoryHelp = $scope.categories[index];
            angular.element(document.querySelector('#category-explanation')).addClass('visible');
        };

        $scope.hideCategoryExplain = function (index) {
            angular.element(document.querySelector('#category-explanation')).removeClass('visible');
        };

        $scope.setGridValue = function (entity, targetUri, code) {
            var payload = {
                uriA: entity.sourceURI,
                uriB: targetUri
            };
            entity.busyCode = code;
            categoriesService.toggleMapping($scope.spec, 'categories', payload).then(function (data) {
                var mappings = $scope.mappings;
                var mapping = mappings[payload.uriA];
                switch (data.action) {
                    case "added":
                        if (mapping) {
                            mapping.push(code);
                        }
                        else {
                            mappings[payload.uriA] = [code];
                        }
                        break;
                    case "removed":
                        if (mapping) {
                            mappings[payload.uriA] = _.filter(mapping, function(c) {
                                return c != code;
                            });
                        }
                        else {
                            console.log("cannot remove", payload);
                        }
                        break;
                }
                delete entity.busyCode;
            });
        };

//        $scope.scrollTo = function (options) {
//            pageScroll.scrollTo(options);
//        };
    };

    CategorySetCtrl.$inject = ["$rootScope", "$scope", "$location", "$routeParams", "categoriesService", "$timeout", "pageScroll"];

    var progressStates = {
        'state-preparing': "Preparing",
        'state-harvesting': "Harvesting",
        'state-collecting': "Collecting",
        'state-adopting': "Adopting",
        'state-generating': "Generating",
        'state-splitting': "Splitting",
        'state-collating': "Collating",
        'state-categorizing': "Categorizing",
        'state-processing': "Processing",
        'state-saving': "Saving",
        'state-error': "Error"
    };

    var CategoryMonitorCtrl = function ($rootScope, $scope, $location, $routeParams, categoriesService, $timeout, user) {

        $scope.lastProgress = null;

        function fetchSheetList() {
            categoriesService.listSheets().then(function (list) {
                $scope.sheets = list.sheets
            });
        }

        fetchSheetList();

        function checkProgress(dataset) {
            categoriesService.datasetProgress(dataset.datasetSpec).then(function (data) {
                if (data.progressType == 'progress-idle') {
                    console.log(dataset.datasetSpec + " is idle, stopping check");
                    dataset.progress = undefined;
                    if (data.errorMessage) {
                        console.log(dataset.datasetSpec + " has error message " + data.errorMessage);
                        dataset.error = data.errorMessage;
                    }
                    if (dataset.refreshAfter) {
                        delete dataset.refreshAfter;
                        console.log(dataset.datasetSpec + " refreshing after progress");
                        refreshInfo();
                    }
                    delete dataset.progress;
                }
                else {
                    console.log(dataset.datasetSpec + " is in progress");
                    $scope.lastProgress = new Date().getTime();
                    dataset.progress = {
                        state: data.progressState,
                        type: data.progressType,
                        count: parseInt(data.count)
                    };
                    createProgressMessage(dataset.progress);
                    dataset.progressCheckerTimeout = $timeout(function () {
                        checkProgress(dataset);
                    }, 900 + Math.floor(Math.random() * 200));
                }
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

        function fetchDatasetList() {
            categoriesService.listDatasets().then(function (array) {
                if ($scope.datasets) _.forEach($scope.datasets, function (dataset) {
                    if (dataset.progressCheckerTimeout) {
                        $timeout.cancel(dataset.progressCheckerTimeout);
                        delete(dataset.progressCheckerTimeout);
                    }
                });
                $scope.datasets = _.filter(array, function (dataset) {
                    return dataset.categoriesInclude == 'true';
                });
                console.log('included datasets', $scope.datasets);
                _.forEach($scope.datasets, function (dataset) {
                    dataset.progressCheckerTimeout = $timeout(function () {
                        checkProgress(dataset);
                    }, 1000 + Math.floor(Math.random() * 1000));
                });
            });
        }

        fetchDatasetList();

        function createProgressMessage(p) {
            if (p.count == 0) p.count = 1;
            var pre = '';
            var post = '';
            var mid = p.count.toString();
            if (p.count > 3) {
                switch (p.type) {
                    case "progress-busy":
                        p.count = 100;
                        mid = "Busy..";
                        break;
                    case "progress-percent":
                        post = " %";
                        break;
                    case "progress-workers":
                        p.count = 100;
                        post = " workers";
                        break;
                    case "progress-pages":
                        p.count = p.count % 100;
                        post = " pages";
                        break;
                }
                if (p.count > 15) {
                    pre = progressStates[p.state] + " ";
                }
            }
            return pre + mid + post;
        }

        $scope.goToCategories = function (ds) {
            $location.path("/categories/" + ds.datasetSpec);
            $location.search({});
        };

        function checkProgressFinished() {
            var now = new Date().getTime();
            if (now - $scope.lastProgress > 2000) {
                fetchSheetList();
            }
            else {
                $timeout(checkProgressFinished, 1000 + Math.floor(Math.random() * 1000));
            }
        }

        $scope.gatherCategoryCounts = function () {
            categoriesService.gatherCategoryCounts().then(function (files) {
                $scope.lastProgress = new Date().getTime();
                fetchDatasetList(); // start progress checkers
                $timeout(checkProgressFinished, 1000 + Math.floor(Math.random() * 1000));
            });
        };

        $scope.sheetUrl = function (name) {
            var absUrl = $location.absUrl();
            var serverUrl = absUrl.substring(0, absUrl.indexOf("#"));
            return serverUrl + "app/sheet/" + name;
        };
    };

    CategoryMonitorCtrl.$inject = ["$rootScope", "$scope", "$location", "$routeParams", "categoriesService", "$timeout", "user"];

    return {
        CategorySetCtrl: CategorySetCtrl,
        CategoryMonitorCtrl: CategoryMonitorCtrl
    };
});
