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

    /**
     * user is not a service, but stems from userResolve (Check ../user/dataset-list-services.js) object used by dashboard.routes.
     */
    var DatasetListCtrl = function ($rootScope, $scope, user, datasetListService, $location, $timeout) {
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
                $scope.newDataset.spec = $scope.newDataset.specTyped.trim().replace(/\W+/g, "-").replace(/[-]+/g, "-").toLowerCase();
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

        datasetListService.listPrefixes().then(function (prefixes) {
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
                            t: dt[1].split('+')[0],
                            dt: dt
                        };
                    }
                }
            );
            if (!stateVisible) {
                dataset.empty = true;
            }
        };

        $scope.fetchDatasetList = function () {
            console.log('fetching list');
            $scope.specFilter = "";
            datasetListService.listDatasets().then(function (array) {
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

        $scope.createDataset = function () {
            datasetListService.create($scope.newDataset.spec, $scope.newDataset.character.code, $scope.newDataset.character.prefix).then(function () {
                $scope.cancelNewFile();
                $scope.newDataset.spec = undefined;
                $scope.fetchDatasetList();
            });
        };

    };

    DatasetListCtrl.$inject = [
        "$rootScope", "$scope", "user", "datasetListService", "$location", "$timeout"
    ];

    // these lists must match with DsInfo.scala

    var metadataFields = [
        "datasetName", "datasetDescription", "datasetAggregator", "datasetOwner", "datasetLanguage", "datasetRights"
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

    var DatasetEntryCtrl = function ($scope, datasetListService, $location, $timeout, $upload) {

        var ds = $scope.dataset;

        var baseUrl = $scope.user ? $scope.user.naveDomain : "http://unknown-nave-domain";
        $scope.searchLink = baseUrl + "/search?qf=delving_spec:" + ds.datasetSpec;
        $scope.apiLink = baseUrl + "/api/search/v1/?qf=delving_spec:" + ds.datasetSpec;
        // todo: note that edm is hardcoded here:
        $scope.oaiPmhLink = baseUrl + "/api/oai-pmh?verb=ListRecords&metadataPrefix=edm&set=" + ds.datasetSpec;
        $scope.apiPathErrors = $scope.user.narthexAPI + "/" + ds.datasetSpec + "/errors";

        function checkProgress() {
            datasetListService.datasetProgress(ds.datasetSpec).then(
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

        function refreshProgress() {
            console.log('refresh progress');
            datasetListService.datasetInfo(ds.datasetSpec).then(function (dataset) {
                if (ds.progressCheckerTimeout) $timeout.cancel(ds.progressCheckerTimeout);
                $scope.decorateDataset(dataset);
                $scope.dataset = dataset;
                dataset.refreshAfter = true;
                dataset.progressCheckerTimeout = $timeout(checkProgress, 1000);
            });
        }

        function refreshInfo() {
            console.log('refresh info');
            datasetListService.datasetInfo(ds.datasetSpec).then(function (dataset) {
                $scope.decorateDataset(dataset);
                $scope.dataset = dataset;
            });
        }

        $scope.leftTabOpen = "metadata";
        $scope.rightTabOpen = ds.harvestURL ? "harvest" : "drop";
        $scope.expanded = false;

        $scope.$watch("expanded", function (expanded) {
            if (expanded) {
                refreshInfo();
            }
            else {
                refreshProgress();
            }
        });

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

        $scope.receiveDropped = function ($files) {
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
                if (p.count > 9) {
                    pre = progressStates[p.state] + " ";
                }
            }
            p.message = pre + mid + post;
        }


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

        function setProperties(propertyList) {
            var payload = {propertyList: propertyList, values: {}};
            _.forEach(propertyList, function (propertyName) {
                payload.values[propertyName] = angular.copy(ds.edit[propertyName]);
            });
            datasetListService.setDatasetProperties(ds.datasetSpec, payload).then(refreshInfo);
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

        $scope.isLater = function (currState, nextState) {
            if (!nextState) return true;
            if (currState) return currState.dt > nextState.dt;
            return false;
        };

        $scope.showInvalidRecordsPage = function () {
            window.open($scope.apiPathErrors, "_blank")
        };

        $scope.goToDataset = function () {
            $location.path("/dataset/" + ds.datasetSpec);
            $location.search({});
        };

        $scope.goToTerms = function () {
            $location.path("/terms/" + ds.datasetSpec);
            $location.search({});
        };

        $scope.goToCategories = function () {
            $location.path("/categories/" + ds.datasetSpec);
            $location.search({});
        };

        $scope.toggleDatasetProduction = function() {
            datasetListService.toggleDatasetProduction().then(function(data) {
                // todo: data.acceptanceOnly?
                refreshInfo()
            });
        };

        function command(command, areYouSure, after) {
            if (areYouSure && !confirm(areYouSure)) return;
            datasetListService.command(ds.datasetSpec, command).then(function (reply) {
                console.log(reply);
            }).then(function () {
                if (after) after();
            });
        }

        $scope.interruptProcessing = function () {
            command("interrupt", "Interrupt processing?", refreshProgress);
        };

        $scope.startGeneratingSip = function () {
            command("start generating sip", null, refreshProgress());
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

        $scope.startRawAnalysis = function () {
            command("start raw analysis", null, refreshProgress);
        };

        $scope.startProcessedAnalysis = function () {
            command("start processed analysis", null, refreshProgress);
        };

        $scope.startSaving = function () {
            command("start saving", null, refreshProgress);
        };

        $scope.clearError = function () {
            command("clear error", null, refreshInfo());
        };

        $scope.deleteDataset = function () {
            command("delete", "Delete dataset?", $scope.fetchDatasetList);
        };

        function fetchSipFileList() {
            datasetListService.listSipFiles(ds.datasetSpec).then(function (data) {
                $scope.sipFiles = (data && data.list && data.list.length) ? data.list : undefined;
            });
        }

        fetchSipFileList();

        $scope.deleteSipZip = function () {
            datasetListService.deleteLatestSipFile(ds.datasetSpec).then(function () {
                fetchSipFileList();
            });
        };
    };

    DatasetEntryCtrl.$inject = ["$scope", "datasetListService", "$location", "$timeout", "$upload"];

    return {
        DatasetListCtrl: DatasetListCtrl,
        DatasetEntryCtrl: DatasetEntryCtrl
    };

});
