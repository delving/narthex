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
            // revert nothing means create
            dashboardService.revert($scope.dataset.validName, "new").then(function () {
                $scope.setNewFileOpen(false);
                $scope.fileOpen = $scope.dataset.validName;
                $scope.dataset.name = undefined;
                fetchDatasetList();
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
            if (!(onlyFile.name.endsWith('.xml.gz') || onlyFile.name.endsWith('.xml'))) {
                alert("Sorry, the file must end with '.xml.gz' or '.xml'");
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
            file.identity = {
                datasetName: file.name
            };
            if (info.status) {
                file.state = info.status.state;
            }
            if (info.origin) {
                file.origin = info.origin.type;
                file.prefix = info.origin.prefix;
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
            if (!info.publication) info.publication = {};
            if (!info.categories) info.categories = {};
            function oaiPmhListRecords(enriched) {
                var absUrl = $location.absUrl();
                var serverUrl = absUrl.substring(0, absUrl.indexOf("#"));
                var start = enriched ? 'oai-pmh/enriched/' : 'oai-pmh/';
                return {
                    prefix: file.prefix,
                    url: serverUrl + start + API_ACCESS_KEY + '?verb=ListRecords&set=' + file.name + "&metadataPrefix=" + file.prefix
                }
            }
            if (info.records) {
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
                        fetchDatasetList()
                    }
                    else {
                        alert("Network problem " + problem.status);
                    }
                }
            )
        }

        function fetchDatasetList() {
            dashboardService.list().then(function (files) {
                _.forEach($scope.files, cancelChecker);
                _.forEach(files, decorateFile);
                $scope.files = files;
                _.forEach(files, function (file) {
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

        $scope.setCategories = function (file) {
            dashboardService.setCategories(file.name, file.info.categories).then(function () {
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

        $scope.revert = function (file, areYouSure, command) {
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
        };

    };

    DashboardCtrl.$inject = [
        "$rootScope", "$scope", "user", "dashboardService", "$location", "$upload", "$timeout", "$routeParams"
    ];

    var DatasetEntryCtrl = function ($scope, dashboardService) {

        $scope.allowTab = function (tabName) {

            function originIs(values, absenceValue) {
                if ($scope.isEmpty($scope.file.info.origin)) return absenceValue;
                var originType = $scope.file.info.origin.type;
                return values.indexOf(originType) >= 0;
            }
            function stateIs(value, absenceValue) {
                if ($scope.isEmpty($scope.file.info.status)) return absenceValue;
                var state = $scope.file.info.status.state;
                return value == state;
            }

            switch (tabName) {
                case 'drop':
                    return originIs(['origin-drop'], true) && stateIs('state-empty', true);
                case 'harvest':
                    return originIs(['origin-harvest','origin-sip-harvest'], true) && stateIs('state-empty', true);
                case 'harvest-cron':
                    return originIs(['origin-harvest', 'origin-sip-harvest'], false) && stateIs('state-sourced', false);
                case 'categories':
                    return stateIs('state-sourced', false) && $scope.file.info.delimit && $scope.file.info.delimit.recordRoot;
                case 'publication':
                    return originIs(['origin-sip-harvest'], false) || $scope.file.recordsTime;
                case 'downloads':
                    return !!$scope.file.recordsTime;
                case 'sip-zip':
                    return originIs(['origin-sip-source', 'origin-sip-harvest'], false);
                default:
                    console.log("ALLOW TAB " + tabName);
                    return false;
            }
        };

        $scope.discardTree = function () {
            $scope.revert($scope.file, "Discard analysis?", "tree");
        };

        $scope.discardRecords = function () {
            $scope.revert($scope.file, "Discard records?", "records");
        };

        $scope.discardSource = function () {
            $scope.revert($scope.file, "Discard source?", "source");
        };

        $scope.interruptProcessing = function () {
            $scope.revert($scope.file, "Interrupt processing?", "interrupt");
        };

        $scope.deleteDataset = function () {
            $scope.revert($scope.file, "Delete dataset?", "revert state");
        };

        function fetchSipFileList() {
            dashboardService.listSipFiles($scope.file.name).then(function (data) {
                if (!data) return;
                var specs = {};
                $scope.sipFiles = _.map(data.list, function (sipFile) {
                    var entry = { sipFileName: sipFile };
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

        $scope.deleteSipZip = function () {
            dashboardService.deleteLatestSipFile($scope.file.name).then(function () {
                fetchSipFileList();
            });
        };

    };

    DatasetEntryCtrl.$inject = ["$scope", "dashboardService"];

    return {
        DashboardCtrl: DashboardCtrl,
        DatasetEntryCtrl: DatasetEntryCtrl
    };

});
