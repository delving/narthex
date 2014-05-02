/**
 * Dashboard controllers.
 */
define(["angular"], function () {
    "use strict";

    /**
     * user is not a service, but stems from userResolve (Check ../user/dashboard-services.js) object used by dashboard.routes.
     */
    var DashboardCtrl = function ($scope, user, dashboardService, fileUpload, $location, $upload, $timeout) {

        $scope.user = user;
        $scope.image = 'jpg';

        $scope.onFileSelect = function ($files) {
            //$files: an array of files selected, each file has name, size, and type.
            for (var i = 0; i < $files.length; i++) {
                var file = $files[i];
                $scope.image = "gif";
                $scope.upload = $upload.upload(
                    {
                        url: '/dashboard/upload', //upload.php script, node.js route, or servlet url
                        // method: POST or PUT,
                        // headers: {'header-key': 'header-value'},
                        // withCredentials: true,
                        data: {myObj: $scope.myModelObj},
                        file: file
                    }
                ).progress(
                    function (evt) {
                        console.log('percent: ' + parseInt(100.0 * evt.loaded / evt.total));
                    }
                ).success(
                    function (data, status, headers, config) {
                        // file is uploaded successfully
                        $scope.image = "jpg";
                        fetchFileList();
                    }
                );
                //.error(...)
                //.then(success, error, progress);
            }
        };

        function checkFileStatus(file) {
            dashboardService.status(file.name).then(function (data) {
                if (data.problem) {
                    file.status = data.problem;
                }
                else {
                    file.status = data;
                    if (file && !file.status.complete) {
                        $timeout(function() { checkFileStatus(file) }, 1000)
                    }
                }
            })
        }

        function fetchFileList() {
            dashboardService.list().then(function (data) {
                $scope.files = data;
                _.forEach($scope.files, function(file) {
                    checkFileStatus(file)
                });
            });
        }

        fetchFileList();

        $scope.viewFile = function(file) {
            $location.path("/dashboard/" + file.name);
        };

    };

    DashboardCtrl.$inject = [
        "$scope", "user", "dashboardService", "fileUpload", "$location", "$upload", "$timeout"
    ];

    var FileDetailCtrl = function ($scope, $routeParams, dashboardService, userService) {

        $scope.fileName = $routeParams.fileName;

        dashboardService.index($scope.fileName).then(function (data) {
            $scope.tree = data;
        });

        $scope.selectNode = function(node) {
            if (!node.lengths.length) return;
            $scope.node = node;
            $scope.sampleSize = 10;
            $scope.histogramSize = 10;
            var user = userService.getUser();
            $scope.uniquePath = "/api/" + user.email + "/" + $scope.fileName + "/unique-text/" + $scope.node.path;
            $scope.histogramPath = "/api/" + user.email + "/" + $scope.fileName + "/histogram-text/" + $scope.node.path;
            dashboardService.nodeStatus($scope.fileName, node.path).then(function(data){
                $scope.status = data;
                $scope.fetchSample(node);
            });
        };

        $scope.fetchSample = function () {
            $scope.currentList = "Sample";
            $scope.otherList = "Histogram";
            dashboardService.sample($scope.fileName, $scope.node.path, $scope.sampleSize).then(function (data) {
                $scope.sample = data;
                $scope.histogram = undefined;
            });
        };

        $scope.fetchHistogram = function () {
            $scope.currentList = "Histogram";
            $scope.otherList = "Sample";
            dashboardService.histogram($scope.fileName, $scope.node.path, $scope.histogramSize).then(function (data) {
                $scope.histogram = data;
                $scope.sample = undefined;
            });
        };

        $scope.fetch = function(listName) {
            if (listName == "Sample") {
                $scope.fetchSample()
            }
            else {
                $scope.fetchHistogram()
            }
        };

        $scope.isMoreSample = function() {
            if (!($scope.status && $scope.status.samples)) return false;
            var which = _.indexOf($scope.status.samples, $scope.sampleSize, true);
            return which < $scope.status.samples.length - 1;
        };

        $scope.moreSample = function() {
            var which = _.indexOf($scope.status.samples, $scope.sampleSize, true);
            $scope.sampleSize = $scope.status.samples[which + 1];
            $scope.fetchSample();
        };

        $scope.isMoreHistogram = function() {
            if (!($scope.status && $scope.status.histograms)) return false;
            var which = _.indexOf($scope.status.histograms, $scope.histogramSize, true);
            return which < $scope.status.histograms.length - 1;
        };

        $scope.moreHistogram = function() {
            var which = _.indexOf($scope.status.histograms, $scope.histogramSize, true);
            $scope.histogramSize = $scope.status.histograms[which + 1];
            $scope.fetchHistogram();
        };
    };

    FileDetailCtrl.$inject = ["$scope", "$routeParams", "dashboardService", "userService"];

    var TreeCtrl = function ($scope) {
        $scope.$watch('tree', function (tree, oldTree) {
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
        TreeCtrl: TreeCtrl,
        TreeNodeCtrl: TreeNodeCtrl,
        FileDetailCtrl: FileDetailCtrl
    };

});
