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
                        url: '/file/upload', //upload.php script, node.js route, or servlet url
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
            console.log("Polling status "+$scope.chosenFile);
            dashboardService.status($scope.chosenFile).then(function (data) {
                if (data.problem) {
                    $scope.fileStatus = data.problem;
                }
                else {
                    $scope.fileStatus = "Count: " + data.count;
                    $scope.analysisComplete = data.complete;
                    if ($scope.chosenFile && !$scope.analysisComplete) {
                        $timeout(pollChosenFileStatus, 500)
                    }
                }
            })
        }

        $scope.watchFile = function (file) {
            $scope.chosenFile = file;
            pollChosenFileStatus();
        };
    };

    DashboardCtrl.$inject = [
        "$scope", "user", "dashboardService", "fileUpload", "$location", "helper", "$upload", "$timeout"
    ];

    var AdminDashboardCtrl = function ($scope, user) {
        $scope.user = user;
    };

    AdminDashboardCtrl.$inject = ["$scope", "user"];

    return {
        DashboardCtrl: DashboardCtrl,
        AdminDashboardCtrl: AdminDashboardCtrl
    };

});
