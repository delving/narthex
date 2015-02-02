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


String.prototype.endsWith = function (suffix) {
    return this.indexOf(suffix, this.length - suffix.length) !== -1;
};

define(["angular"], function () {
    "use strict";


    var progressStates = {
        'state-harvesting': "Harvesting",
        'state-collecting': "Collecting",
        'state-adopting': "Adopting",
        'state-generating': "Generating",
        'state-splitting': "Splitting",
        'state-collating': "Collating",
        'state-categorizing': "Categorizing",
        'state-processing': "Processing",
        'state-error': "Error"
    };

    /**
     * user is not a service, but stems from userResolve (Check ../user/datasets-services.js) object used by dashboard.routes.
     */
    var DatasetsCtrl = function ($rootScope, $scope, user, datasetsService, $location, $timeout) {
        if (user == null) $location.path("/");
        $scope.user = user;
        $scope.uploading = false;
        $scope.filteredDatasets = [];
        $scope.files = [];
        $scope.percent = null;
        $scope.dropSupported = false;
        $scope.newFileOpen = false;
        $scope.newDataset = {};
        $scope.specFilter = "";
        $scope.categoriesEnabled = user.categoriesEnabled;

        function checkNewEnabled() {
            if ($scope.newDataset.specTyped)
                $scope.newDataset.spec = $scope.newDataset.specTyped.trim().replace(/\W+/g, "_").replace(/_+/g, "_").toLowerCase();
            else
                $scope.newDataset.spec = "";
            $scope.newDataset.enabled = $scope.newDataset.spec.length && $scope.newDataset.character;
        }

        $scope.$watch("newDataset.specTyped", checkNewEnabled);
        $scope.$watch("newDataset.character", checkNewEnabled);

        function filterDatasets() {
            var filter = $scope.specFilter.trim();
            if (filter) {
                $scope.filteredDatasets = _.filter($scope.datasets, function (dataset) {
                    return dataset.datasetSpec.toLowerCase().indexOf(filter.toLowerCase()) >= 0;
                });
            }
            else {
                $scope.filteredDatasets = $scope.datasets;
            }
        }

        $scope.$watch("specFilter", filterDatasets);

        datasetsService.listPrefixes().then(function (prefixes) {
            $scope.characters = _.map(prefixes, function (prefix) {
                // for each prefix we should be able to accept a pre-mapped file
                return {
                    title: "Mapped to '" + prefix.toUpperCase() + "' format",
                    code: "character-mapped",
                    prefix: prefix
                };
            });
            if ($scope.characters.length == 1) {
                $scope.newDataset.character = $scope.characters[0];
            }
        });

        $scope.createDataset = function () {
            datasetsService.create($scope.newDataset.spec, $scope.newDataset.character.code, $scope.newDataset.character.prefix).then(function () {
                $scope.cancelNewFile();
                $scope.newDataset.name = undefined;
                $scope.fetchDatasetList();
            });
        };

        $scope.nonEmpty = function (obj) {
            return !_.isEmpty(obj)
        };

        $scope.isEmpty = function (obj) {
            return _.isEmpty(obj)
        };

        $scope.cancelNewFile = function () {
            $scope.newFileOpen = false;
        };

        $scope.setDropSupported = function () {
            $scope.dropSupported = true;
        };

        $scope.decorateDataset = function (dataset) {
            dataset.edit = angular.copy(dataset);
            dataset.apiMappings = user.narthexAPI + '/' + dataset.datasetSpec + '/mappings';
//            if (dataset.character) dataset.prefix = info.character.prefix;
//            split the states into date and time
            var stateVisible = false;
            _.forEach(
                [
                    'stateRaw', 'stateRawAnalyzed', 'stateSourced',
                    'stateMappable', 'stateProcessable', 'stateProcessed',
                    'stateAnalyzed', 'stateSaved'
                ],
                function (stateName) {
                    var time = dataset[stateName];
                    if (time) {
                        stateVisible = true;
                        var dt = time.split('T');
                        dataset[stateName] = {
                            d: dt[0],
                            t: dt[1].split('+')[0]
                        };
                    }
                }
            );
            if (!stateVisible) {
                dataset.empty = true;
            }
        };

//        $scope.replaceDataset = function (dataset) {
//            function replaceInList(list, dataset) {
//                return _.map(list, function (ds) {
//                    if (ds.datasetSpec == dataset.datasetSpec) {
//                        console.log('replaced', ds);
//                        return dataset;
//                    }
//                    else {
//                        return ds;
//                    }
//                });
//            }
//            replaceInList($scope.datasets, dataset);
//            replaceInList($scope.filteredDatasets, dataset);
//        };
//
        $scope.fetchDatasetList = function () {
            $scope.specFilter = "";
            datasetsService.listDatasets().then(function (array) {
                // kill existing progress checkers
                if ($scope.datasets) _.forEach($scope.datasets, function (dataset) {
                    if (dataset.progressCheckerTimeout) {
                        $timeout.cancel(dataset.progressCheckerTimeout);
                        delete(dataset.progressCheckerTimeout);
                    }
                });
                _.forEach(array, $scope.decorateDataset);
                $scope.datasets = $scope.filteredDatasets = array;
            });
        };

        $scope.fetchDatasetList();

    };

    DatasetsCtrl.$inject = [
        "$rootScope", "$scope", "user", "datasetsService", "$location", "$timeout"
    ];

    // these lists must match with DsInfo.scala

    var metadataFields = [
        "datasetName", "datasetDescription", "datasetOwner", "datasetLanguage", "datasetRights"
    ];

    var harvestFields = [
        "harvestType", "harvestURL", "harvestDataset", "harvestPrefix", "harvestSearch"
    ];

    var harvestCronFields = [
        "harvestPreviousTime", "harvestDelay", "harvestDelayUnit"
    ];

    var publishFields = [
        "publishOAIPMH", "publishIndex", "publishLOD"
    ];

    var categoriesFields = [
        "categoriesInclude"
    ];

    var DatasetEntryCtrl = function ($scope, datasetsService, $location, $timeout, $upload) {

        var ds = $scope.dataset;

        $scope.tabOpen = "metadata";
        $scope.expanded = false;

        if (!ds) return;

        ds.progressCheckerTimeout = $timeout(checkProgress, 1000 + Math.floor(Math.random() * 1000));

        $scope.$watch("dataset", function (newDs) {
            ds = newDs;
            setUnchanged()
        });

        function fileDropped($files, after) {
            //$files: an array of files selected, each file has name, size, and type.  Take the first only.
            if (!($files.length && !ds.uploading)) return;
            var onlyFile = $files[0];
            if (!(onlyFile.name.endsWith('.xml.gz') || onlyFile.name.endsWith('.xml') || onlyFile.name.endsWith('.sip.zip'))) {
                alert("Sorry, the file must end with '.xml.gz' or '.xml' or '.sip.zip'");
                return;
            }
            ds.uploading = true;
            $upload.upload({
                url: '/narthex/app/dataset/' + ds.datasetSpec + '/upload',
                file: onlyFile
            }).progress(
                function (evt) {
                    if (ds.uploading) ds.uploadPercent = parseInt(100.0 * evt.loaded / evt.total);
                }
            ).success(
                function () {
                    ds.uploading = false;
                    ds.uploadPercent = null;
                    if (after) after();
                }
            ).error(
                function (data, status, headers, config) {
                    ds.uploading = false;
                    ds.uploadPercent = null;
                    console.log("Failure during upload: data", data);
                    console.log("Failure during upload: status", status);
                    console.log("Failure during upload: headers", headers);
                    console.log("Failure during upload: config", config);
                    alert(data.problem);
                }
            );
        }

        ds.fileDropped = function ($files) {
            fileDropped($files, refreshProgress);
        };

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
            p.message = pre + mid + post;
        }

        function checkProgress() {
            datasetsService.datasetProgress(ds.datasetSpec).then(
                function (data) {
                    if (data.progressType == 'progress-idle') {
                        console.log(ds.datasetSpec + " is idle, stopping check");
                        ds.progress = undefined;
                        if (data.errorMessage) {
                            console.log(ds.datasetSpec + " has error message " + data.errorMessage);
                            ds.error = data.errorMessage;
                        }
                        if (ds.refreshAfter) {
                            delete ds.refreshAfter;
                            console.log(ds.datasetSpec + " refreshing after progress");
                            refreshInfo();
                        }
                        delete ds.progress;
                    }
                    else {
                        console.log(ds.datasetSpec + " is in progress");
                        ds.progress = {
                            state: data.progressState,
                            type: data.progressType,
                            count: parseInt(data.count)
                        };
                        createProgressMessage(ds.progress);
                        ds.progressCheckerTimeout = $timeout(checkProgress, 900 + Math.floor(Math.random() * 200));
                    }
                },
                function (problem) {
                    if (problem.status == 404) {
                        alert("Processing problem with " + ds.datasetSpec);
                    }
                    else {
                        alert("Network problem " + problem.status);
                    }
                }
            );
        }

        $scope.getIcon = function () {
            if (ds.stateSaved) { // todo: mapped -> processed, etc
                return "fa-database"
            }
            else if (ds.stateAnalyzed) {
                return "fa-eye"
            }
            else {
                return 'fa-folder-o';
            }
        };

        function unchanged(fieldNameList) {
            var unchanged = true;
            _.forEach(fieldNameList, function (fieldName) {
                if (!angular.equals(ds[fieldName], ds.edit[fieldName])) {
//                    console.log("changed " + fieldName);
                    unchanged = false;
                }
            });
            return unchanged;
        }

        function setUnchanged() {
            $scope.unchangedMetadata = unchanged(metadataFields);
            $scope.unchangedPublish = unchanged(publishFields);
            $scope.unchangedHarvest = unchanged(harvestFields);
            $scope.unchangedHarvestCron = unchanged(harvestCronFields);
            $scope.unchangedCategories = unchanged(categoriesFields);
        }

        $scope.$watch("dataset.edit", setUnchanged, true);

        function refreshProgress() {
            console.log('refresh progress');
            datasetsService.datasetInfo(ds.datasetSpec).then(function (dataset) {
                if (ds.progressCheckerTimeout) $timeout.cancel(ds.progressCheckerTimeout);
                $scope.decorateDataset(dataset);
                $scope.dataset = dataset;
                dataset.refreshAfter = true;
                dataset.progressCheckerTimeout = $timeout(checkProgress, 1000);
            });
        }

        function refreshInfo() {
            console.log('refresh info');
            datasetsService.datasetInfo(ds.datasetSpec).then(function (dataset) {
                $scope.decorateDataset(dataset);
                $scope.dataset = dataset;
            });
        }

        function setProperties(propertyList) {
            var payload = {propertyList: propertyList, values: {}};
            _.forEach(propertyList, function (propertyName) {
                payload.values[propertyName] = angular.copy(ds.edit[propertyName]);
            });
            datasetsService.setProperties(ds.datasetSpec, payload).then(refreshInfo);
        }

        $scope.setMetadata = function () {
            setProperties(metadataFields);
        };

        $scope.setPublish = function () {
            setProperties(publishFields);
        };

        $scope.setCategories = function () {
            setProperties(categoriesFields)
        };

        $scope.setHarvest = function () {
            setProperties(harvestFields);
        };

        $scope.setHarvestCron = function () {
            setProperties(harvestCronFields);
        };

        $scope.viewFile = function () {
            $location.path("/dataset/" + ds.datasetSpec);
            $location.search({});
        };

        function command(command, areYouSure, after) {
            if (areYouSure && !confirm(areYouSure)) return;
            datasetsService.command(ds.datasetSpec, command).then(function (reply) {
                console.log(reply);
            }).then(function () {
                if (after) after();
            });
        }

        $scope.interruptProcessing = function () {
            command("interrupt", "Interrupt processing?", refreshProgress);
        };

        $scope.discardSource = function () {
            command("remove source", "Discard source?", refreshInfo);
        };

        $scope.discardProcessed = function () {
            command("remove processed", "Discard processed data?", refreshInfo);
        };

        $scope.discardTree = function () {
            command("remove tree", "Discard analysis?", refreshInfo);
        };

        $scope.startProcessing = function () {
            command("start processing", null, refreshProgress);
        };

        $scope.startFirstHarvest = function () {
            command("start first harvest", "Erase existing data?", refreshProgress);
        };

        $scope.startAnalysis = function () {
            command("start analysis", null, refreshProgress);
        };

        $scope.startSaving = function () {
            command("start saving", null, refreshProgress);
        };

        $scope.deleteDataset = function () {
            command("delete", "Delete dataset?", $scope.fetchDatasetList);
        };

        function fetchSipFileList() {
            datasetsService.listSipFiles(ds.datasetSpec).then(function (data) {
                $scope.sipFiles = (data && data.list && data.list.length) ? data.list : undefined;
            });
        }

        fetchSipFileList();

        $scope.deleteSipZip = function () {
            datasetsService.deleteLatestSipFile(ds.datasetSpec).then(function () {
                fetchSipFileList();
            });
        };
    };

    DatasetEntryCtrl.$inject = ["$scope", "datasetsService", "$location", "$timeout", "$upload"];

    return {
        DatasetsCtrl: DatasetsCtrl,
        DatasetEntryCtrl: DatasetEntryCtrl
    };

});
