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
                        console.log(data);
                        $scope.image = "jpg";
                        fetchFileList();
                    }
                );
                //.error(...)
                //.then(success, error, progress);
            }
        };

        function fetchFileList() {
            dashboardService.list().then(function (data) {
                $scope.files = data;
            })
        }

        fetchFileList();

        function checkFileStatus() {
            dashboardService.status($scope.chosenFile).then(function (data) {
                if (data.problem) {
                    $scope.fileStatus = data.problem;
                }
                else {
                    $scope.fileStatus = data;
                    if ($scope.chosenFile && !$scope.fileStatus.complete) {
                        $timeout(checkFileStatus, 1000)
                    }
                }
            })
        }

        $scope.viewFile = function() {
            if ($scope.chosenFile) {
                $location.path("/dashboard/" + $scope.chosenFile);
            }
        };

        $scope.watchFile = function (file) {
            $scope.chosenFile = file;
            checkFileStatus();
        };

    };

    DashboardCtrl.$inject = [
        "$scope", "user", "dashboardService", "fileUpload", "$location", "$upload", "$timeout"
    ];

    var FileDetailCtrl = function ($scope, $routeParams, dashboardService) {
        $scope.fileName = $routeParams.fileName;

        console.log("fetching analysis");
        dashboardService.index($scope.fileName).then(function (data) {
            $scope.tree = data;
        });

        $scope.fetchSample = function (node, size) {
            dashboardService.sample($scope.fileName, node.path, size).then(function (data) {
                node.sample = data.sample;
            });
        };

        $scope.fetchHistogram = function (node, size) {
            dashboardService.histogram($scope.fileName, node.path, size).then(function (data) {
                node.histogram = data.histogram;
            });
        };
    };

    FileDetailCtrl.$inject = ["$scope", "$routeParams", "dashboardService"];

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
