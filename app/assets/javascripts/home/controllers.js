/**
 * Home controllers.
 */
define(["angular"], function (angular) {
    "use strict";

    /** Controls the index page */
    var HomeCtrl = function ($scope, $rootScope, fileUpload, homeService, $location, helper, $upload) {


        $rootScope.pageTitle = "Welcome";

        $scope.image = 'jpg';

        function fetchFileList() {
            homeService.listFiles().then(function(data) {
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
    HomeCtrl.$inject = ["$scope", "$rootScope", "fileUpload", "homeService", "$location", "helper", "$upload"];

    /** Controls the header */
    var HeaderCtrl = function ($scope, userService, helper, $location) {
        // Wrap the current user from the service in a watch expression
        $scope.$watch(function () {
            var user = userService.getUser();
            return user;
        }, function (user) {
            $scope.user = user;
        }, true);

        $scope.logout = function () {
            userService.logout();
            $scope.user = undefined;
            $location.path("/");
        };
    };
    HeaderCtrl.$inject = ["$scope", "userService", "helper", "$location"];

    /** Controls the footer */
    var FooterCtrl = function (/*$scope*/) {
    };
    //FooterCtrl.$inject = ["$scope"];

    return {
        HeaderCtrl: HeaderCtrl,
        FooterCtrl: FooterCtrl,
        HomeCtrl: HomeCtrl
    };

});
