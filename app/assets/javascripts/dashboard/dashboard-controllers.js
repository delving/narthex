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


/*
 file.origin:
 origin-drop
 origin-harvest
 origin-sip
 file.state:
 state-deleted
 state-empty
 state-sourced
 file.progressType:
 progress-idle (here progressType is undefined)
 progress-busy
 progress-percent
 progress-workers
 progress-pages
 file.progressState
 state-idle
 state-harvesting
 state-collecting
 state-generating
 state-splitting
 state-collating
 state-saving
 state-error
 */

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
        $scope.activeTab = $routeParams.tab || "files";
        $scope.fileOpen = $routeParams.open || $rootScope.fileOpen || "";

        var absUrl = $location.absUrl();
        $scope.apiPrefix = absUrl.substring(0, absUrl.indexOf("#")) + "api/" + API_ACCESS_KEY;
        $scope.dropSupported = false;
        $scope.newFileOpen = false;
        $scope.dataset = { name: "", prefix: "", fileName: ""};

        function setSearch() {
            $location.search({ tab: $scope.activeTab, open: $scope.fileOpen });
        }

        function setFileName() {
            var ds = $scope.dataset;
            if (!ds.name) ds.name = "";
            if (!ds.prefix) ds.prefix = "";
            var name = ds.name.replace(/\W+/g, "_").replace(/_+/g, "_");
            var prefix = ds.prefix.toLowerCase().replace(/\W/g, "").replace(/_+/g, "_");
            ds.validFileName = name.length > 0 && prefix.length > 0 && prefix.length < 9;
            ds.fileName = name + "__" + prefix
        }

        $scope.$watch("dataset.name", setFileName);
        $scope.$watch("dataset.prefix", setFileName);

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
            setSearch();
        };

        $scope.setNewFileOpen = function (value) {
            $scope.newFileOpen = value;
        };

        $scope.setActiveTab = function (tab) {
            $scope.activeTab = tab;
            setSearch();
        };

        $scope.createDataset = function () {
            if ($scope.dataset.validFileName) {
                // revert nothing means create
                dashboardService.revert($scope.dataset.fileName, "new").then(function () {
                    $scope.setNewFileOpen(false);
                    $scope.fileOpen = $scope.dataset.fileName;
                    $scope.dataset.name = $scope.dataset.prefix = "";
                    fetchDatasetList();
                });
            }
        };

        function oaiPmhListRecords(fileName, enriched) {
            var absUrl = $location.absUrl();
            var serverUrl = absUrl.substring(0, absUrl.indexOf("#"));
            var fileNameParts = fileName.split("__");
            var start = enriched ? 'oai-pmh/enriched/' : 'oai-pmh/';
            return serverUrl + start + API_ACCESS_KEY + '?verb=ListRecords&set=' + fileName + "&metadataPrefix=" + fileNameParts[1];
        }

        $scope.setDropSupported = function () {
            $scope.dropSupported = true;
        };

        function fileDropped(file, $files) {
            //$files: an array of files selected, each file has name, size, and type.  Take the first only.
            if (!($files.length && !file.uploading)) return;
            var onlyFile = $files[0];
            if (!(onlyFile.name.endsWith('.xml.gz') || onlyFile.name.endsWith('.xml'))) {
                alert("Sorry, the file must end with '.xml.gz' or '.xml'");
                return;
            }
            file.uploading = true;
            $upload.upload({
                url: '/narthex/dashboard/' + file.name + '/upload',
                file: onlyFile
            }).progress(
                function (evt) {
                    if (file.uploading) file.uploadPercent = parseInt(100.0 * evt.loaded / evt.total);
                }
            ).success(
                function () {
                    file.uploading = false;
                    file.uploadPercent = null;
                    fetchDatasetList();
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
            'state-generating': "Generating source",
            'state-splitting': "Splitting fields",
            'state-collating': "Collating values",
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
            file.oaiPmhListRecords = oaiPmhListRecords(file.name, false);
            file.oaiPmhListEnrichedRecords = oaiPmhListRecords(file.name, true);
            file.fileDropped = function ($files) {
                fileDropped(file, $files)
            };
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
            if (info.tree) {
                file.treeTime = info.tree.time;
            }
            if (info.records) {
                file.recordsTime = info.records.time;
            }
        }

        function cancelChecker(file) {
            if (file.checker) {
                $timeout.cancel(file.checker);
                delete(file.checker);
            }
        }

        function checkProgress(file) {
            dashboardService.datasetInfo(file.name).then(function (info) {
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

        function fetchDatasetList() {
            dashboardService.list().then(function (files) {
                _.forEach($scope.files, cancelChecker);
                _.forEach(files, decorateFile);
                $scope.files = files;
                _.forEach(files, function(file) {
                    if (file.progress) checkProgress(file)
                });
            });
        }

        fetchDatasetList();

        $scope.setMetadata = function (file) {
            dashboardService.setMetadata(file.name, file.info.metadata).then(function () {
                fetchDatasetList();
            });
        };

        $scope.setPublication = function (file) {
            dashboardService.setPublication(file.name, file.info.publication).then(function () {
                fetchDatasetList();
            });
        };

        $scope.setHarvestCron = function (file) {
            dashboardService.setHarvestCron(file.name, file.info.harvestCron).then(function () {
                fetchDatasetList();
            });
        };

        $scope.startHarvest = function (file) {
            $scope.setFileOpen("");
            dashboardService.harvest(file.name, file.info.harvest).then(function () {
                fetchDatasetList();
            });
        };

        $scope.startAnalysis = function (file) {
            $scope.setFileOpen("");
            dashboardService.analyze(file.name).then(function (data) {
                console.log("start analysis reply", data);
                fetchDatasetList();
            });
        };

        $scope.saveRecords = function (file) {
            $scope.setFileOpen("");
            dashboardService.saveRecords(file.name).then(function () {
                $timeout(
                    function () {
                        checkProgress(file)
                    },
                    $scope.checkDelay
                )
            });
        };

        $scope.revert = function(file, areYouSure, command) {
            if (areYouSure && !confirm(areYouSure)) return;
            dashboardService.revert(file.name, command).then(function (data) {
                fetchDatasetList();
                if (command == 'interrupt') {
                    $scope.fileOpen = file.name;
                    setSearch();
                }
            });
        };

        $scope.viewFile = function (file) {
            $location.path("/dataset/" + file.name);
            $location.search({});
            $rootScope.addRecentDataset(file.name, $location.absUrl())
        };

        function fetchSipFileList() {
            dashboardService.listSipFiles().then(function (data) {
                var specs = {};
                $scope.sipFiles = _.map(data.list, function (sipFile) {
                    var entry = { fileName: sipFile };
                    var part = sipFile.match(/sip_(.+)__(\d+)_(\d+)_(\d+)_(\d+)_(\d+)__(.*).zip/);
                    if (part) {
                        var spec = part[1];
                        if (specs[spec]) entry.expendable = true;
                        specs[spec] = true;
                        entry.details = {
                            spec: spec,
                            date: new Date(
                                parseInt(part[2]), parseInt(part[3]), parseInt(part[4]),
                                parseInt(part[5]), parseInt(part[6]), 0),
                            uploadedBy: part[7]
                        };
                    }
                    return entry;
                });
                if (!$scope.sipFiles.length) $scope.sipFiles = undefined
            });
        }

        fetchSipFileList();

        $scope.deleteSipZip = function (file) {
            dashboardService.deleteSipFile(file.fileName).then(function () {
                fetchSipFileList();
            });
        };
    };

    DashboardCtrl.$inject = [
        "$rootScope", "$scope", "user", "dashboardService", "$location", "$upload", "$timeout", "$routeParams"
    ];

    var FileEntryCtrl = function ($scope, dashboardService) {

        $scope.tab = "metadata";

        $scope.allowTab = function (file, tabName) {
            switch (tabName) {
                case 'drop':
                    if ($scope.isEmpty(file.info.origin)) return true;
                    return file.info.origin.type == 'origin-drop' && file.info.status.state == 'state-empty';
                case 'harvest':
                    if ($scope.isEmpty(file.info.origin)) return true;
                    return file.info.origin.type == 'origin-harvest' && file.info.status.state == 'state-empty';
                case 'harvest-cron':
                    if ($scope.isEmpty(file.info.origin)) return true;
                    return file.info.origin.type == 'origin-harvest' && file.info.status.state == 'state-sourced';
                case 'publication':
                case 'downloads':
                    return !!file.recordsTime;
                default:
                    console.log("ALLOW TAB " + tabName);
                    return false;
            }
        };

        $scope.discardTree = function (file) {
            $scope.revert(file, "Discard analysis?", "tree");
        };

        $scope.discardRecords = function (file) {
            $scope.revert(file, "Discard records?", "records");
        };

        $scope.discardSource = function (file) {
            $scope.revert(file, "Discard source?", "source");
        };

        $scope.interruptProcessing = function (file) {
            $scope.revert(file, "Interrupt processing?", "interrupt");
        };

    };

    FileEntryCtrl.$inject = ["$scope", "dashboardService"];

    return {
        DashboardCtrl: DashboardCtrl,
        FileEntryCtrl: FileEntryCtrl
    };

});
