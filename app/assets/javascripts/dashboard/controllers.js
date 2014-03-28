/**
 * Dashboard controllers.
 */
define(["angular"], function () {
    "use strict";

    /**
     * user is not a service, but stems from userResolve (Check ../user/services.js) object used by dashboard.routes.
     */
    var DashboardCtrl = function ($scope, user, dashboardService, fileUpload, $location, helper, $upload) {

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
                $scope.upload = $upload.upload({
                    url: '/file/upload', //upload.php script, node.js route, or servlet url
                    // method: POST or PUT,
                    // headers: {'header-key': 'header-value'},
                    // withCredentials: true,
                    data: {myObj: $scope.myModelObj},
                    file: file
                }).progress(function (evt) {
                    console.log('percent: ' + parseInt(100.0 * evt.loaded / evt.total));
                }).success(function (data, status, headers, config) {
                    // file is uploaded successfully
                    console.log(data);
                    $scope.image = "jpg";
                    fetchFileList();
                });
                //.error(...)
                //.then(success, error, progress);
            }
        };

    };
    DashboardCtrl.$inject = ["$scope", "user", "dashboardService", "fileUpload", "$location", "helper", "$upload"];

    var AdminDashboardCtrl = function ($scope, user) {
        $scope.user = user;
    };
    AdminDashboardCtrl.$inject = ["$scope", "user"];

    return {
        DashboardCtrl: DashboardCtrl,
        AdminDashboardCtrl: AdminDashboardCtrl
    };

});
