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

    /**
     * user is not a service, but stems from userResolve (Check ../user/datasets-services.js) object used by dashboard.routes.
     */
    var DatasetsCtrl = function ($rootScope, $scope, user, datasetsService, $location, $timeout) {
        if (user == null) $location.path("/");
        $scope.user = user;
        $scope.uploading = false;
        $scope.filteredFiles = [];
        $scope.files = [];
        $scope.percent = null;
        $scope.dropSupported = false;
        $scope.newFileOpen = false;
        $scope.dataset = { name: "" };
        $scope.specFilter = "";
        $scope.categoriesEnabled = user.categoriesEnabled;

        function setNewDataset() {
            var ds = $scope.dataset;
            if (!ds.name) ds.name = "";
            var name = ds.name.trim().replace(/\W+/g, "_").replace(/_+/g, "_").toLowerCase();
            ds.validName = name;
            if (name.replace(/_/, "").length == 0 || !ds.prefix) ds.validName = undefined
        }

        $scope.$watch("dataset.name", setNewDataset);
        $scope.$watch("dataset.prefix", setNewDataset);

        $scope.$watch("specFilter", function (filter) {
            filter = filter.trim();
            if (filter) {
                $scope.filteredFiles = _.filter($scope.files, function (file) {
                    return file.name.toLowerCase().indexOf(filter.toLowerCase()) >= 0;
                });
            }
            else {
                $scope.filteredFiles = $scope.files;
            }
        });

        $scope.createDataset = function () {
            datasetsService.create($scope.dataset.validName, $scope.dataset.prefix).then(function () {
                $scope.cancelNewFile();
                $scope.dataset.name = undefined;
                $scope.fetchDatasetList();
            });
        };

        $scope.nonEmpty = function (obj) {
            return !_.isEmpty(obj)
        };

        $scope.isEmpty = function (obj) {
            return _.isEmpty(obj)
        };

        $scope.cancelNewFile = function() {
            $scope.newFileOpen = false;
        };

        $scope.setDropSupported = function () {
            $scope.dropSupported = true;
        };

        // treeTime, mappedTime, identity { datasetName, prefix, recordCount, name, dataProvider }
        $scope.decorateFile = function (file, info) {
            if (info) {
                file.info = info;
            }
            else {
                info = file.info;
            }
            file.originalInfo = angular.copy(file.info);
            if (info.error) file.error = info.error.message;
            file.apiMappings = user.narthexAPI + '/' + file.name + '/mappings';
            file.state = info.status.state;
            file.datasetName = file.name;
            if (info.character) file.prefix = info.character.prefix;
            if (!info.harvest) info.harvest = {};
            if (!info.metadata) info.metadata = {};
            if (!info.publication) info.publication = {};
            if (!info.categories) info.categories = {};
            if (info.tree && info.tree.ready == 'true') {
                file.treeTime = info.tree.time;
            }
            else {
                file.treeTime = undefined;
            }
            if (info.source && info.source.ready == 'true') {
                file.sourceTime = info.source.time;
                file.recordCount = info.source.recordCount;
            }
            else {
                file.sourceTime = undefined;
            }
            if (info.records && info.records.ready == 'true') {
                file.recordCount = info.records.recordCount;
                file.mappedTime = info.records.time;
            }
            else {
                file.mappedTime = undefined;
            }
        };

        $scope.fetchDatasetList = function () {
            datasetsService.listDatasets().then(function (files) {
                _.forEach($scope.files, function (file) {
                    if (file.progressCheckerTimeout) {
                        $timeout.cancel(file.progressCheckerTimeout);
                        delete(file.progressCheckerTimeout);
                    }
                });
                _.forEach(files, function (file) {
                    $scope.decorateFile(file, undefined);
                    file.tabOpen = 'metadata';
                });
                $scope.files = $scope.filteredFiles = files;
                $scope.specFilter = "";
            });
        };

        $scope.fetchDatasetList();

        datasetsService.listPrefixes().then(function (prefixes) {
            $scope.prefixes = prefixes;
            $scope.dataset.prefix = prefixes.length ? prefixes[0] : undefined
        });
    };

    DatasetsCtrl.$inject = [
        "$rootScope", "$scope", "user", "datasetsService", "$location", "$timeout"
    ];

    var DatasetEntryCtrl = function ($scope, datasetsService, $location, $timeout, $upload) {

        $scope.file.progressCheckerTimeout = $timeout(checkProgress, 1000 + Math.floor(Math.random() * 1000));

        function fileDropped(file, $files, after) {
            //$files: an array of files selected, each file has name, size, and type.  Take the first only.
            if (!($files.length && !file.uploading)) return;
            var onlyFile = $files[0];
            if (!(onlyFile.name.endsWith('.xml.gz') || onlyFile.name.endsWith('.xml') || onlyFile.name.endsWith('.sip.zip'))) {
                alert("Sorry, the file must end with '.xml.gz' or '.xml' or '.sip.zip'");
                return;
            }
            file.uploading = true;
            $upload.upload({
                url: '/narthex/app/' + file.name + '/upload',
                file: onlyFile
            }).progress(
                function (evt) {
                    if (file.uploading) file.uploadPercent = parseInt(100.0 * evt.loaded / evt.total);
                }
            ).success(
                function () {
                    file.uploading = false;
                    file.uploadPercent = null;
                    if (after) after();
                }
            ).error(
                function (data, status, headers, config) {
                    file.uploading = false;
                    file.uploadPercent = null;
                    console.log("Failure during upload: data", data);
                    console.log("Failure during upload: status", status);
                    console.log("Failure during upload: headers", headers);
                    console.log("Failure during upload: config", config);
                    alert(data.problem);
                }
            );
        }

        $scope.file.fileDropped = function ($files) {
            fileDropped($scope.file, $files, function() {
                refreshProgress();
            });
        };

        var stateNames = {
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
                    pre = stateNames[p.state] + " ";
                }
            }
            p.message = pre + mid + post;
        }

        function checkProgress() {
            datasetsService.datasetProgress($scope.file.name).then(
                function (data) {
                    if (data.progressType == 'progress-idle') {
                        $scope.file.progress = undefined;
                        if (data.errorMessage) {
                            console.log($scope.file.name + " has error message " + data.errorMessage);
                            $scope.file.error = data.errorMessage;
                        }
                        if ($scope.file.refreshAfter) {
                            delete $scope.file.refreshAfter;
                            refreshInfo();
                        }
                        delete $scope.file.progress;
                    }
                    else {
                        console.log($scope.file.name + " is in progress");
                        $scope.file.progress = {
                            state: data.progressState,
                            type: data.progressType,
                            count: parseInt(data.count)
                        };
                        createProgressMessage($scope.file.progress);
                        $scope.file.progressCheckerTimeout = $timeout(checkProgress, 900 + Math.floor(Math.random() * 200));
                    }
                },
                function (problem) {
                    if (problem.status == 404) {
                        alert("Processing problem with " + $scope.file.name);
                    }
                    else {
                        alert("Network problem " + problem.status);
                    }
                }
            );
        }

        $scope.getIcon = function () {
            if ($scope.file.mappedTime) {
                return "fa-database"
            }
            else if ($scope.file.treeTime) {
                return "fa-eye"
            }
            else if ($scope.fileOpen == $scope.file.name) {
                return 'fa-folder-open-o';
            }
            else {
                return 'fa-folder-o';
            }
        };

        function nonEmpty(obj) {
            if (obj)
                return obj;
            else
                return {};
        }

        function compareForSave() {
            $scope.unchangedMetadata = angular.equals($scope.file.info.metadata, nonEmpty($scope.file.originalInfo.metadata));
            $scope.unchangedPublication = angular.equals($scope.file.info.publication, nonEmpty($scope.file.originalInfo.publication));
            $scope.unchangedCategories = angular.equals($scope.file.info.categories, nonEmpty($scope.file.originalInfo.categories));
            $scope.unchangedHarvestCron = angular.equals($scope.file.info.harvestCron, nonEmpty($scope.file.originalInfo.harvestCron));
        }

        function refreshProgress() {
            datasetsService.datasetInfo($scope.file.name).then(function (info) {
                $scope.decorateFile($scope.file, info);
                $scope.file.refreshAfter = true;
                if ($scope.file.progressCheckerTimeout) $timeout.cancel($scope.file.progressCheckerTimeout);
                $scope.file.progressCheckerTimeout = $timeout(checkProgress, 1000);
            });
        }

        function refreshInfo() {
            datasetsService.datasetInfo($scope.file.name).then(function (info) {
                $scope.file.info = info;
                $scope.decorateFile($scope.file);
                compareForSave();
            });
        }

        $scope.$watch("file.info", compareForSave, true);

        $scope.setMetadata = function () {
            datasetsService.setMetadata($scope.file.name, $scope.file.info.metadata).then(refreshInfo);
        };

        $scope.setPublication = function () {
            // todo: publish in index implies publish oaipmh
            datasetsService.setPublication($scope.file.name, $scope.file.info.publication).then(refreshInfo);
            // todo: show the user it has happened!
        };

        $scope.setCategories = function () {
            datasetsService.setCategories($scope.file.name, $scope.file.info.categories).then(refreshInfo);
        };

        $scope.setHarvestCron = function () {
            datasetsService.setHarvestCron($scope.file.name, $scope.file.info.harvestCron).then(refreshInfo);
        };

        $scope.startHarvest = function () {
            datasetsService.harvest($scope.file.name, $scope.file.info.harvest).then(refreshProgress);
        };

        $scope.viewFile = function () {
            $location.path("/dataset/" + $scope.file.name);
            $location.search({});
        };

        function command(command, areYouSure, after) {
            if (areYouSure && !confirm(areYouSure)) return;
            datasetsService.command($scope.file.name, command).then(function (reply) {
                console.log(reply);
                if (command == "delete") {
                    $scope.fetchDatasetList();
                }
                else {
                    refreshProgress();
                }
            }).then(function() {
                if (after) after();
            });
        }

        $scope.interruptProcessing = function () {
            command("interrupt", "Interrupt processing?");
        };

        $scope.discardSource = function () {
            command("remove source", "Discard source?");
        };

        $scope.discardMapped = function () {
            command("remove mapped", "Discard mapped data?");
        };

        $scope.discardTree = function () {
            command("remove tree", "Discard analysis?");
        };

        $scope.startMapping = function () {
            command("start mapping", null, refreshProgress);
        };

        $scope.startAnalysis = function () {
            command("start analysis", null, refreshProgress);
        };

        $scope.deleteDataset = function () {
            $scope.command("Delete dataset?", "delete");
        };

        function fetchSipFileList() {
            datasetsService.listSipFiles($scope.file.name).then(function (data) {
                $scope.sipFiles = (data && data.list && data.list.length) ? data.list : undefined;
            });
        }

        fetchSipFileList();

        $scope.deleteSipZip = function () {
            datasetsService.deleteLatestSipFile($scope.file.name).then(function () {
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
