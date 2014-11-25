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

var API_ACCESS_KEY = "secret"; // todo: find a better way

define(["angular"], function () {
    "use strict";

    /**
     * user is not a service, but stems from userResolve (Check ../user/dashboard-services.js) object used by dashboard.routes.
     */
    var DashboardCtrl = function ($rootScope, $scope, user, dashboardService, $location, $upload, $timeout, $routeParams) {

        $scope.user = user;
        $scope.uploading = false;
        $scope.files = [];
        $scope.percent = null;
        $scope.fileOpen = $routeParams.open || $rootScope.fileOpen || "";
        $scope.tabOpen = $routeParams.tab || $rootScope.tabOpen || "metadata";

        var absUrl = $location.absUrl();
        $scope.apiPrefix = absUrl.substring(0, absUrl.indexOf("#")) + "api/" + API_ACCESS_KEY;
        $scope.dropSupported = false;
        $scope.newFileOpen = false;
        $scope.dataset = { name: "" };

        function setSearch() {
            $location.search({open: $scope.fileOpen, tab: $scope.tabOpen });
        }

        $scope.$watch("dataset.name", function () {
            var ds = $scope.dataset;
            if (!ds.name) ds.name = "";
            var name = ds.name.trim().replace(/\W+/g, "_").replace(/_+/g, "_");
            ds.validName = (name.replace(/_/, "").length > 0) ? name : undefined;
        });

        $scope.createDataset = function () {
            dashboardService.command($scope.dataset.validName, "create").then(function () {
                $scope.setNewFileOpen(false);
                $scope.dataset.name = undefined;
                $scope.fetchDatasetList();
                $scope.setFileOpen($scope.dataset.validName);
            });
        };

        $scope.nonEmpty = function (obj) {
            return !_.isEmpty(obj)
        };

        $scope.isEmpty = function (obj) {
            return _.isEmpty(obj)
        };

        $scope.setFileOpen = function (file) {
            if ($scope.fileOpen == file.name || file.progress) {
                $scope.fileOpen = "";
            }
            else {
                $scope.fileOpen = file.name;
            }
            $scope.tabOpen = 'metadata';
            setSearch();
        };

        $scope.setNewFileOpen = function (value) {
            $scope.newFileOpen = value;
        };

        $scope.setTabOpen = function(tab) {
            $scope.tabOpen = tab;
            setSearch();
        };

        $scope.setDropSupported = function () {
            $scope.dropSupported = true;
        };

        function fileDropped(file, $files) {
            //$files: an array of files selected, each file has name, size, and type.  Take the first only.
            if (!($files.length && !file.uploading)) return;
            var onlyFile = $files[0];
            if (!(onlyFile.name.endsWith('.xml.gz') || onlyFile.name.endsWith('.xml') || onlyFile.name.endsWith('.sip.zip'))) {
                alert("Sorry, the file must end with '.xml.gz' or '.xml' or '.sip.zip'");
                return;
            }
            file.uploading = true;
            $upload.upload({
                url: '/narthex/dashboard/' + file.name + '/upload/' + file.prefix,
                file: onlyFile
            }).progress(
                function (evt) {
                    if (file.uploading) file.uploadPercent = parseInt(100.0 * evt.loaded / evt.total);
                }
            ).success(
                function () {
                    file.uploading = false;
                    file.uploadPercent = null;
                    $scope.setFileOpen("");
                    $scope.fetchDatasetList();
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

        var stateNames = {
            'state-harvesting': "Harvesting from server",
            'state-collecting': "Collecting identifiers",
            'state-adopting': "Adopting data",
            'state-generating': "Generating source",
            'state-splitting': "Splitting fields",
            'state-collating': "Collating values",
            'state-categorizing': "Categorizing records",
            'state-saving': "Saving to database",
            'state-updating': "Updating database",
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
            return pre + mid + post;
        }

        // progressState, progressType, treeTime, recordsTime, identity { datasetName, prefix, recordCount, name, dataProvider }
        function decorateFile(file) {

            function oaiPmhListRecords(enriched) {
                var absUrl = $location.absUrl();
                var serverUrl = absUrl.substring(0, absUrl.indexOf("#"));
                var start = enriched ? 'oai-pmh/enriched/' : 'oai-pmh/';
                return {
                    prefix: file.prefix,
                    url: serverUrl + start + API_ACCESS_KEY + '?verb=ListRecords&set=' + file.name + "&metadataPrefix=" + file.prefix
                }
            }

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
            file.apiMappings = $scope.apiPrefix + '/' + file.name + '/mappings';
            file.fileDropped = function ($files) {
                fileDropped(file, $files)
            };
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
            if (info.source && info.source.ready == 'true') {
                file.sourceTime = info.source.time;
                file.recordCount = info.source.recordCount;
            }
            if (info.records && info.records.ready == 'true') {
                file.recordCount = info.records.recordCount;
                file.recordsTime = info.records.time;
                if (file.prefix) {
                    file.oaiPmhListRecords = oaiPmhListRecords(false);
                    file.oaiPmhListEnrichedRecords = oaiPmhListRecords(true);
                }
            }
        }

        function cancelChecker(file) {
            if (file.checker) {
                $timeout.cancel(file.checker);
                delete(file.checker);
            }
        }

        function checkProgress(file) {
            dashboardService.datasetInfo(file.name).then(
                function (info) {
                    file.info = info;
                    decorateFile(file);
                    if (!file.progress) return;
                    file.checker = $timeout(function () {
                        checkProgress(file)
                    }, 1000);
                },
                function (problem) {
                    if (problem.status == 404) {
                        alert("Processing problem with " + file.name);
                        $scope.fetchDatasetList()
                    }
                    else {
                        alert("Network problem " + problem.status);
                    }
                }
            )
        }

        $scope.fetchDatasetList = function () {
            dashboardService.list().then(function (files) {
                _.forEach($scope.files, cancelChecker);
                _.forEach(files, decorateFile);
                $scope.files = files;
                _.forEach(files, function (file) {
                    if (file.progress) checkProgress(file)
                });
            });
        };

        $scope.fetchDatasetList();
    };

    DashboardCtrl.$inject = [
        "$rootScope", "$scope", "user", "dashboardService", "$location", "$upload", "$timeout", "$routeParams"
    ];

    var DatasetEntryCtrl = function ($scope, dashboardService, $location, $timeout) {

        var file = $scope.file;

        function refresh() {
            $scope.fetchDatasetList();
        }

        $scope.setMetadata = function () {
            dashboardService.setMetadata(file.name, file.info.metadata).then(refresh);
        };

        $scope.setPublication = function () {
            // todo: publish in index implies publish oaipmh
            dashboardService.setPublication(file.name, file.info.publication).then(refresh);
            // todo: show the user it has happened!
        };

        $scope.setCategories = function () {
            dashboardService.setCategories(file.name, file.info.categories).then(refresh);
        };

        $scope.setHarvestCron = function () {
            dashboardService.setHarvestCron(file.name, file.info.harvestCron).then(refresh);
        };

        $scope.startHarvest = function () {
            $scope.setFileOpen("");
            dashboardService.harvest(file.name, file.info.harvest).then(refresh);
        };

        $scope.startAnalysis = function () {
            $scope.setFileOpen("");
            dashboardService.analyze(file.name).then(refresh);
        };

        $scope.saveRecords = function () {
            $scope.setFileOpen("");
            dashboardService.saveRecords(file.name).then(refresh);
        };

        $scope.viewFile = function () {
            $location.path("/dataset/" + file.name);
            $location.search({});
        };

        $scope.command = function (areYouSure, command) {
            if (areYouSure && !confirm(areYouSure)) return;
            dashboardService.command(file.name, command).then(function (data) {
                refresh();
                if (command == 'interrupt') $scope.setFileOpen(file.name);
            });
        };

        $scope.interruptProcessing = function () {
            $scope.command("Interrupt processing?", "interrupt");
        };

        $scope.discardSource = function () {
            $scope.command("Discard source?", "remove source");
        };

        $scope.discardTree = function () {
            $scope.command("Discard analysis?", "remove tree");
        };

        $scope.discardRecords = function () {
            $scope.command("Discard records?", "remove records");
        };

        $scope.deleteDataset = function () {
            $scope.command("Delete dataset?", "delete");
        };

        function fetchSipFileList() {
            dashboardService.listSipFiles(file.name).then(function (data) {
                $scope.sipFiles = (data && data.list && data.list.length)? data.list : undefined;
            });
        }
        fetchSipFileList();

        $scope.deleteSipZip = function () {
            dashboardService.deleteLatestSipFile(file.name).then(function () {
                fetchSipFileList();
            });
        };

    };

    DatasetEntryCtrl.$inject = ["$scope", "dashboardService", "$location", "$timeout"];

    return {
        DashboardCtrl: DashboardCtrl,
        DatasetEntryCtrl: DatasetEntryCtrl
    };

});
