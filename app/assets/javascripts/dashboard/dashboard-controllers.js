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

var API_ACCESS_KEY = "secret";

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
        revertPrompt: 'Discard analysis',
        viewSchema: true
    },
    'state-saving': {
        label: 'Saving',
        css: 'label-warning',
        faIcon: 'fa-cogs',
        revertState: 'state-analyzed',
        revertPrompt: 'Cancel record saving',
        viewSchema: true
    },
    'state-saved': {
        label: 'Saved',
        css: 'label-success',
        faIcon: 'fa-database',
        revertState: 'state-analyzed',
        revertPrompt: 'Delete saved records',
        viewSchema: true
    },
    'state-published': {
        label: 'Published',
        css: 'label-success',
        faIcon: 'fa-share-square-o',
        revertState: 'state-analyzed',
        revertPrompt: 'Delete saved records',
        viewSchema: true,
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
        $scope.fileOpen = $routeParams.open || "";

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

        $scope.setFileOpen = function (file) {
            if ($scope.fileOpen == file.name) {
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
            return (file.info.status.percent > 0 || file.info.status.workers > 0)
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
                var part = onlyFile.name.match(/(.+)__(.+).xml.gz/);
                if (!part) {
                    alert("Sorry, the file must be named SPEC__PREFIX.xml.gz");
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
                        name: parts[0],
                        prefix: parts[1],
                        recordCount: file.info.delimit.recordCount > 0 ? file.info.delimit.recordCount : undefined
                    };

                })
            });
        }

        $scope.startHarvest = function (file) {
            dashboardService.harvest(file.name, file.info.harvest).then(function () {
                fetchDatasetList();
            });
        };

        $scope.startAnalysis = function (file) {
            dashboardService.analyze(file.name).then(function () {
                fetchDatasetList();
            });
        };

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

    String.prototype.hashCode = function () {
        var self = this;
        var hash = 0;
        if (self.length == 0) return hash;
        for (var i = 0; i < self.length; i++) {
            var c = self.charCodeAt(i);
            hash = ((hash << 5) - hash) + c;
            hash = hash & hash; // Convert to 32bit integer
        }
        return hash;
    };

    var FileDetailCtrl = function ($rootScope, $scope, $routeParams, $timeout, $location, dashboardService, pageScroll) {
        var MAX_FOR_VOCABULARY = 12500;
        $scope.fileName = $routeParams.fileName;

        var absUrl = $location.absUrl();
        $scope.apiPrefix = absUrl.substring(0, absUrl.indexOf("#")) + "api/" + API_ACCESS_KEY;

        $scope.scrollTo = function (options) {
            pageScroll.scrollTo(options);
        };

        $scope.selectedNode = null;
        $scope.uniqueIdNode = null;
        $scope.recordRootNode = null;

        dashboardService.datasetInfo($scope.fileName).then(function (datasetInfo) {
            $scope.datasetInfo = datasetInfo;
            dashboardService.index($scope.fileName).then(function (tree) {
                function sortKids(node) {
                    if (!node.kids.length) return;
                    node.kids = _.sortBy(node.kids, function (kid) {
                        return kid.tag.toLowerCase();
                    });
                    for (var index = 0; index < node.kids.length; index++) {
                        sortKids(node.kids[index]);
                    }
                }

                sortKids(tree);

                function selectNode(path, node) {
                    if (!path.length) {
                        $scope.selectNode(node);
                    }
                    else {
                        var tag = path.shift();
                        for (var index = 0; index < node.kids.length; index++) {
                            if (tag == node.kids[index].tag) {
                                selectNode(path, node.kids[index]);
                                return;
                            }
                        }
                    }
                }
                $scope.tree = tree;
                if ($routeParams.path) selectNode($routeParams.path.substring(1).split('/'), { tag: '', kids: [$scope.tree]});

                function setDelim(node, recordRoot, recordContainer, uniqueId) {
                    if (node.path == recordRoot) {
                        $scope.recordRootNode = node;
                    }
                    else if (node.path == uniqueId) {
                        $scope.uniqueIdNode = node;
                    }
                    else {
                        var rootPart = node.path.substring(0, recordContainer.length);
                        if (rootPart != recordContainer) {
                            node.path = "";
                        }
                        else {
                            var recPath = node.path.substring(recordContainer.length);
                            node.sourcePath = $rootScope.orgId + "/" + $scope.fileName + recPath;
                        }
                    }
                    for (var index = 0; index < node.kids.length; index++) {
                        setDelim(node.kids[index], recordRoot, recordContainer, uniqueId);
                    }
                }


                var recordRoot = datasetInfo.delimit.recordRoot;
                if (recordRoot) {
                    var recordContainer = recordRoot.substring(0, recordRoot.lastIndexOf("/"));
                    var uniqueId = datasetInfo.delimit.uniqueId;
                    setDelim(tree, recordRoot, recordContainer, uniqueId);
                    if (parseInt(datasetInfo.delimit.recordCount) <= 0 && $scope.uniqueIdNode) {
                        $scope.setUniqueIdNode($scope.uniqueIdNode); // trigger setting record count
                    }
                }

                function filterSourcePath(node, sourcePaths) {
                    if (node.sourcePath && sourcePaths.indexOf(node.sourcePath) < 0) {
                        delete node.sourcePath
                    }
                    for (var index = 0; index < node.kids.length; index++) {
                        filterSourcePath(node.kids[index], sourcePaths);
                    }
                }

                dashboardService.getSourcePaths($scope.fileName).then(function (data) {
                    filterSourcePath(tree, data.sourcePaths);
                });
            });
        });

        $scope.goToDashboard = function () {
            $location.path("/dashboard");
            $location.search({});
        };

        $scope.goToTerms = function (node) {
            if (node && node != $scope.selectedNode) return;
            $location.path("/terms/" + $scope.fileName);
            $location.search({
                path: $routeParams.path,
                size: $scope.status.histograms[$scope.status.histograms.length - 1]
            });
            var lastPath = $routeParams.path.substring($routeParams.path.lastIndexOf("/"));
            $rootScope.addRecentTerms($scope.fileName + " (" + lastPath + ")", $location.absUrl())
        };

        function setActiveView(activeView) {
            $scope.activeView = activeView;
            $location.search({
                path: $routeParams.path,
                view: activeView
            });
        }

        function setActivePath(activePath) {
            $scope.activePath = activePath;
            $location.search({
                path: activePath,
                view: $routeParams.view
            });
        }

        $scope.selectNode = function (node, $event) {
            if ($event) $event.stopPropagation();
            if (node.lengths.length == 0 || node.path.length == 0) return;
            $scope.selectedNode = node;
            setActivePath(node.path);
            dashboardService.nodeStatus($scope.fileName, node.path).then(function (data) {
                $scope.status = data;
                var filePath = node.path.replace(":", "_").replace("@", "_");
                $scope.apiPathUnique = $scope.apiPrefix + "/" + $scope.fileName + "/unique" + filePath;
                $scope.apiPathHistogram = $scope.apiPrefix + "/" + $scope.fileName + "/histogram" + filePath;
                $scope.sampleSize = 100;
                $scope.histogramSize = 100;
                switch ($routeParams.view) {
                    case 'sample':
                        $scope.fetchSample();
                        break;
                    case 'lengths':
                        $scope.fetchLengths();
                        break;
                    default:
                        $scope.fetchHistogram();
                        break;
                }
            });
        };

        $scope.setUniqueIdNode = function (node) {
            function selectFirstEmptyWithCount(node, count) {
                if (!node.lengths.length && node.count == count) {
                    return node;
                }
                else for (var index = 0; index < node.kids.length; index++) {
                    var emptyWithCount = selectFirstEmptyWithCount(node.kids[index], count);
                    if (emptyWithCount) return emptyWithCount;
                }
                return undefined;
            }

            var recordRootNode = selectFirstEmptyWithCount($scope.tree, node.count);
            if (recordRootNode) {
                $scope.recordRootNode = recordRootNode;
                $scope.uniqueIdNode = node;
                var body = {
                    recordRoot: $scope.recordRootNode.path,
                    uniqueId: $scope.uniqueIdNode.path,
                    recordCount: $scope.uniqueIdNode.count
                };
                dashboardService.setRecordDelimiter($scope.fileName, body).then(function () {
                    console.log("record delimiter set");
                });
            }
        };

        $scope.fetchLengths = function () {
            $scope.sample = undefined;
            $scope.histogram = undefined;
            setActiveView("lengths");
        };

        $scope.fetchSample = function () {
            dashboardService.sample($scope.fileName, $routeParams.path, $scope.sampleSize).then(function (data) {
                $scope.sample = data;
                $scope.histogram = undefined;
            });
            setActiveView("sample");
        };

        $scope.fetchHistogram = function () {
            dashboardService.histogram($scope.fileName, $routeParams.path, $scope.histogramSize).then(function (data) {
                _.forEach(data.histogram, function (entry) {
                    var percent = (100 * entry[0]) / $scope.selectedNode.count;
                    entry.push(percent);
                });
                $scope.histogram = data;
                $scope.sample = undefined;
                $scope.histogramUnique = data.histogram[0] && data.histogram[0][0] == 1;
                $scope.histogramVocabulary = (!$scope.histogramUnique) && ($scope.status.uniqueCount < MAX_FOR_VOCABULARY);
            });
            setActiveView("histogram");
        };

        $scope.isMoreSample = function () {
            if (!($scope.status && $scope.status.samples)) return false;
            var which = _.indexOf($scope.status.samples, $scope.sampleSize, true);
            return which < $scope.status.samples.length - 1;
        };

        $scope.moreSample = function () {
            var which = _.indexOf($scope.status.samples, $scope.sampleSize, true);
            $scope.sampleSize = $scope.status.samples[which + 1];
            $scope.fetchSample();
        };

        $scope.isMoreHistogram = function () {
            if (!($scope.status && $scope.status.histograms)) return false;
            var which = _.indexOf($scope.status.histograms, $scope.histogramSize, true);
            return which < $scope.status.histograms.length - 1;
        };

        $scope.moreHistogram = function () {
            var which = _.indexOf($scope.status.histograms, $scope.histogramSize, true);
            $scope.histogramSize = $scope.status.histograms[which + 1];
            $scope.fetchHistogram();
        };

        $scope.getX = function () {
            return function (d) {
                return d[0];
            }
        };

        $scope.getY = function () {
            return function (d) {
                return d[1];
            }
        };

        $scope.getColor = function () {
            var lengthName = ["0", "1", "2", "3", "4", "5", "6-10", "11-15", "16-20", "21-30", "31-50", "50-100", "100-*"];
            var noOfColors = lengthName.length;
            var frequency = 4 / noOfColors;

            function toHex(c) {
                var hex = c.toString(16);
                return hex.length == 1 ? "0" + hex : hex;
            }

            function rgbToHex(r, g, b) {
                return "#" + toHex(r) + toHex(g) + toHex(b);
            }

            var colorLookup = {};
            for (var walk = 0; walk < noOfColors; ++walk) {
                var r = Math.floor(Math.sin(frequency * walk + 0) * (127) + 128);
                var g = Math.floor(Math.sin(frequency * walk + 1) * (127) + 128);
                var b = Math.floor(Math.sin(frequency * walk + 3) * (127) + 128);
                colorLookup[lengthName[walk]] = rgbToHex(r, g, b);
            }
            return function (d) {
                return colorLookup[d.data[0]];
            };
        };

    };

    FileDetailCtrl.$inject = ["$rootScope", "$scope", "$routeParams", "$timeout", "$location", "dashboardService", "pageScroll"];

    var FileListCtrl = function ($scope) {
    };

    FileListCtrl.$inject = ["$scope"];

    var FileEntryCtrl = function ($scope) {


        $scope.allowHarvest = function(file) {
            if (!$scope.nonEmpty(file.info.origin)) return true;
            return file.info.origin.type == 'origin-harvest'
        };

        $scope.allowDrop = function(file) {
            if (!$scope.nonEmpty(file.info.origin)) return true;
            return file.info.origin.type == 'origin-drop'
        };

        $scope.allowAnalysis = function(file) {
            return file.info.status.state == 'state-ready';
        };

        $scope.allowSaveRecords = function(file) {
            return file.info.status.state == 'state-analyzed' && file.info.delimit.recordCount > 0;
        };

        $scope.allowPublish = function(file) {
            return file.info.status.state == 'state-saved';
        };

        $scope.allowUnpublish = function(file) {
            return file.info.status.state == 'state-published';
        };

        $scope.revert = function(file) {
            $scope.revertToState(file, file.stateBlock.revertState, file.stateBlock.revertPrompt + '?')
        };
    };

    FileEntryCtrl.$inject = ["$scope"];

    var TreeCtrl = function ($scope) {
        $scope.$watch('tree', function (tree) {
            if (tree) {
                $scope.node = tree;
            }
        });
    };

    TreeCtrl.$inject = ["$scope"];

    var TreeNodeCtrl = function ($scope) {
//        $scope.setNode = function(node) {
//            console.log("node", node);
//            $scope.node = node;
//        };
    };

    TreeNodeCtrl.$inject = ["$scope"];

    return {
        DashboardCtrl: DashboardCtrl,
        FileEntryCtrl: FileEntryCtrl,
        FileListCtrl: FileListCtrl,
        TreeCtrl: TreeCtrl,
        TreeNodeCtrl: TreeNodeCtrl,
        FileDetailCtrl: FileDetailCtrl
    };

});
