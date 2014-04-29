/**
 * Dashboard controllers.
 */
define(["angular"], function () {
    "use strict";

    /**
     * user is not a service, but stems from userResolve (Check ../user/services.js) object used by dashboard.routes.
     */
    var DashboardCtrl = function ($scope, user, dashboardService, fileUpload, $location, helper, $upload, $timeout) {

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

        function pollChosenFileStatus() {
            console.log("Polling status " + $scope.chosenFile);
            dashboardService.status($scope.chosenFile).then(function (data) {
                if (data.problem) {
                    $scope.fileStatus = data.problem;
                }
                else {
                    $scope.fileStatus = "Count: " + data.count;
                    $scope.analysisComplete = data.complete;
                    if ($scope.chosenFile && !$scope.analysisComplete) {
//                        console.log("Poll again");
                        $timeout(pollChosenFileStatus, 500)
                    }
                    else {
                        console.log("fetching analysis");
                        dashboardService.index($scope.chosenFile).then(function (data) {
                            console.log("tree=", data);
                            $scope.tree = data;
                        });
                    }
                }
            })
        }

        $scope.watchFile = function (file) {
            $scope.chosenFile = file;
            pollChosenFileStatus();
        };

        $scope.fetchSample = function(node, size) {
            dashboardService.sample($scope.chosenFile, node.path, size).then(function (data) {
                console.log("sample=", data);
                node.sample = data.sample;
            });
        };

        $scope.fetchHistogram = function(node, size) {
            dashboardService.histogram($scope.chosenFile, node.path, size).then(function (data) {
                console.log("histogram=", data);
                node.histogram = data.histogram;
            });
        };

    };

    DashboardCtrl.$inject = [
        "$scope", "user", "dashboardService", "fileUpload", "$location", "helper", "$upload", "$timeout"
    ];

    var AdminDashboardCtrl = function ($scope, user) {
        $scope.user = user;
    };

    AdminDashboardCtrl.$inject = ["$scope", "user"];

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
        AdminDashboardCtrl: AdminDashboardCtrl // todo: is this used?
    };

});
