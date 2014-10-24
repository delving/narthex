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

var STATE_BLOCK = {
    'state-empty': {
        label: 'Empty',
        css: 'label-inverse',
        faIcon: 'fa-folder-o',
        revertState: 'state-deleted',
        revertPrompt: 'Delete dataset'
    },
    'state-harvesting': {
        label: 'Harvesting',
        css: 'label-warning',
        faIcon: 'fa-cogs',
        revertState: 'state-empty',
        revertPrompt: 'Cancel harvesting'
    },
    'state-collecting': {
        label: 'Collecting',
        css: 'label-warning',
        faIcon: 'fa-cogs',
        revertState: 'state-empty',
        revertPrompt: 'Cancel collecting'
    },
    'state-ready': {
        label: 'Ready',
        css: 'label-info',
        faIcon: 'fa-folder',
        revertState: 'state-empty',
        revertPrompt: 'Empty dataset'
    },
    'state-splitting': {
        label: 'Splitting',
        css: 'label-warning',
        faIcon: 'fa-cogs',
        revertState: 'state-ready',
        revertPrompt: 'Cancel splitting'
    },
    'state-analyzing': {
        label: 'Analyzing',
        css: 'label-warning',
        faIcon: 'fa-cogs',
        revertState: 'state-ready',
        revertPrompt: 'Cancel analyzing'
    },
    'state-analyzed': {
        label: 'Analyzed',
        css: 'label-success',
        faIcon: 'fa-folder',
        revertState: 'state-ready',
        revertPrompt: 'Discard analysis'
    },
    'state-saving': {
        label: 'Saving',
        css: 'label-warning',
        faIcon: 'fa-cogs',
        revertState: 'state-analyzed',
        revertPrompt: 'Cancel record saving'
    },
    'state-saved': {
        label: 'Saved',
        css: 'label-success',
        faIcon: 'fa-database',
        revertState: 'state-analyzed',
        revertPrompt: 'Delete saved records',
        viewTerminology: true
    }
};

define(["angular"], function () {
    "use strict";

    /**
     * user is not a service, but stems from userResolve (Check ../user/dashboard-services.js) object used by dashboard.routes.
     */
    var DashboardCtrl = function ($rootScope, $scope, user, dashboardService, $location, $upload, $timeout, $routeParams) {

        $scope.user = user;
        $scope.uploading = false;
        $scope.files = [];
        $scope.checkDelay = 1000;
        $scope.lastStatusCheck = 0;
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
            if ($scope.fileOpen == file.name) {
                $scope.fileOpen = "";
            }
            else {
                $scope.fileOpen = file.name;
            }
//            $rootScope.saveFileOpen($scope.fileOpen);
            setSearch();
        };

        $scope.setNewFileOpen = function (value) {
            $scope.newFileOpen = value;
        };

        $scope.createDataset = function () {
            if ($scope.dataset.validFileName) {
                // empty state means create a dataset if it's not there
                dashboardService.goToState($scope.dataset.fileName, 'state-empty').then(function () {
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

        function timeSinceStatusCheck() {
            var now = new Date().getTime();
            return now - $scope.lastStatusCheck;
        }

        $scope.setDropSupported = function () {
            $scope.dropSupported = true;
        };

        function isActive(file) {
            return file.info.progress && file.info.progress.type != 'progress-idle';
        }

        function checkDatasetStatus(file) {
            dashboardService.datasetInfo(file.name).then(function (datasetInfo) {
                if (!file.info || !file.info.status) {
                    console.log("MISSING STATUS BEFORE", file);
                    return
                }
                var state = file.info.status.state;
                file.stateBlock = STATE_BLOCK[state];
                file.info = datasetInfo;
                if (!file.info || !file.info.status) {
                    console.log("MISSING STATUS AFTER", file);
                    return
                }
                if (state != file.info.status.state || isActive(file)) {
                    var time = file.info.status.time || 0;
                    var now = new Date().getTime();
                    if (now - time > 1000 * 60 * 3) { // stale in 10 minutes
                        file.staleStatus = true;
                    }
                    else {
                        var interval = timeSinceStatusCheck();
                        if (interval > 1000) { // don't change the scope thing too often
                            $scope.lastStatusCheck = now;
                        }
                        file.checker = $timeout(
                            function () {
                                checkDatasetStatus(file)
                            },
                            $scope.checkDelay
                        );
                    }
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

        function fileDropped(file, $files) {
            //$files: an array of files selected, each file has name, size, and type.  Take the first only.
            if ($files.length && !file.uploading) {
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
        }

        function fetchDatasetList() {
            dashboardService.list().then(function (data) {
                _.forEach($scope.files, function (file) {
                    file.stateBlock = STATE_BLOCK[file.info.status.state];
                    if (file.checker) {
                        $timeout.cancel(file.checker);
                        file.checker = undefined;
                        console.log("cancelling " + file.name);
                    }
                });
                $scope.files = data;
                _.forEach($scope.files, checkDatasetStatus);
                _.forEach($scope.files, function (file) {
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
                    if (file.info.delimit && file.info.delimit.recordCount > 0) {
                        file.identity.recordCount = file.info.delimit.recordCount;
                    }
                    if (file.info.metadata) {
                        file.identity.name = file.info.metadata.name;
                        file.identity.dataProvider = file.info.metadata.dataProvider;
                    }
                })
            });
        }

        $scope.setMetadata = function(file) {
            dashboardService.setMetadata(file.name, file.info.metadata).then(function () {
                fetchDatasetList();
            });
        };

        $scope.setPublication = function(file) {
            dashboardService.setPublication(file.name, file.info.publication).then(function () {
                fetchDatasetList();
            });
        };

        $scope.startHarvest = function (file) {
            dashboardService.harvest(file.name, file.info.harvest).then(function () {
                fetchDatasetList();
            });
        };

        $scope.setHarvestCron = function (file) {
            dashboardService.setHarvestCron(file.name, file.info.harvestCron).then(function () {
                fetchDatasetList();
            });
        };

        $scope.startAnalysis = function (file) {
            dashboardService.analyze(file.name).then(function () {
                fetchDatasetList();
            });
        };

        // todo: this must change!
        $scope.setPublished = function (file, published) {
            var toState = published ? 'state-published' : 'state-saved';
            dashboardService.goToState(file.name, toState).then(function (data) {
                file.info.status.state = data.state;
                file.stateBlock = STATE_BLOCK[data.state];
            });
        };

        fetchDatasetList();

        $scope.setActiveTab = function (tab) {
            $scope.activeTab = tab;
            setSearch();
        };

        $scope.viewFile = function (file) {
            $location.path("/dataset/" + file.name);
            $location.search({});
            $rootScope.addRecentDataset(file.name, $location.absUrl())
        };

        $scope.revertToState = function (file, state, areYouSure) {
            if (areYouSure && !confirm(areYouSure))return;
            dashboardService.goToState(file.name, state).then(function () {
                fetchDatasetList();
            });
        };

        $scope.saveRecords = function (file) {
            dashboardService.saveRecords(file.name).then(function () {
                $timeout(
                    function () {
                        checkDatasetStatus(file)
                    },
                    $scope.checkDelay
                )
            });
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

    var FileEntryCtrl = function ($scope) {

        $scope.tab = "metadata";

        $scope.allowTab = function(file, tabName) {
            switch (tabName) {
                case 'drop':
                    if ($scope.isEmpty(file.info.origin)) return true;
                    return file.info.origin.type == 'origin-drop' && file.info.status.state == 'state-empty';
                case 'harvest':
                    if ($scope.isEmpty(file.info.origin)) return true;
                    return file.info.origin.type == 'origin-harvest' && file.info.status.state == 'state-empty';
                case 'harvest-cron':
                    if ($scope.isEmpty(file.info.origin)) return true;
                    return file.info.origin.type == 'origin-harvest' && file.info.status.state == 'state-saved';
                case 'publication':
                    if ($scope.isEmpty(file.info.status)) return false;
                    return file.info.status.state == 'state-saved';
                case 'downloads':
                    if ($scope.isEmpty(file.info.status)) return false;
                    return file.info.status.state == 'state-saved';
                default:
                    console.log("ALLOW TAB "+tabName);
                    return false;
            }
        };

        $scope.allowAnalysis = function(file) {
            if ($scope.isEmpty(file.info.status)) return false;
            var okState =  file.info.status.state == 'state-ready' || file.info.status.state == 'state-saved';
            return okState && !(file.info.analysis.present == 'true')
        };

        $scope.allowSaveRecords = function(file) {
            if ($scope.isEmpty(file.info.status)) return false;
            if ($scope.isEmpty(file.info.delimit)) return false;
            return file.info.status.state == 'state-analyzed' && file.info.delimit.recordCount > 0;
        };

        $scope.revert = function(file) {
            $scope.revertToState(file, file.stateBlock.revertState, file.stateBlock.revertPrompt + '?')
        };
    };

    FileEntryCtrl.$inject = ["$scope"];

    return {
        DashboardCtrl: DashboardCtrl,
        FileEntryCtrl: FileEntryCtrl
    };

});
