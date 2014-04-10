/**
 * Dashboard controllers.
 */
define(["angular"], function () {
    "use strict";

    /**
     * user is not a service, but stems from userResolve (Check ../user/services.js) object used by dashboard.routes.
     */
    var DashboardCtrl = function (
        $scope, user, dashboardService, fileUpload, $location, helper, $upload, $timeout
        ) {

        $scope.user = user;

        $scope.image = 'jpg';

        function fetchFileList() {
            dashboardService.listFiles().then(function(data) {
                $scope.files = data;
            })
        }

        fetchFileList();

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

        function pollStatus() {
            dashboardService.statusFile($scope.chosenFile).then(function(data) {
                if (data.problem) {
                    $scope.fileStatus = data.problem;
                }
                else {
                    $scope.fileStatus = 'progressCount:' + data.progressCount + ', completed=' + data.completed
                    $timeout(pollStatus, 500)
                }
            })
        }

        $scope.analyzeFile = function(file) {
            dashboardService.analyzeFile(file).then(function(data) {
                $scope.chosenFile = file;
                if (data.problem) {
                    $scope.fileLength = data.problem; // todo: silly, fix this
                }
                else {
                    $scope.fileLength = data.fileLength;
                    pollStatus()
                }
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

    return {
        DashboardCtrl: DashboardCtrl,
        AdminDashboardCtrl: AdminDashboardCtrl
    };

});
