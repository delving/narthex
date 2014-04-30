/**
 * Home controllers.
 */
define(["angular"], function (angular) {
    "use strict";

    /** Controls the index page */
    var HomeCtrl = function ($scope, $rootScope, $cookies, $location) {
        $scope.token = $cookies["XSRF-TOKEN"];
        if ($scope.token) {
            $location.path("/dashboard")
        }
    };
    HomeCtrl.$inject = ["$scope", "$rootScope", "$cookies", "$location"];

    /** Controls the header */
    var HeaderCtrl = function ($scope, userService, $location) {
        // Wrap the current user from the service in a watch expression
        $scope.$watch(
            function () {
                return userService.getUser();
            },
            function (user) {
                $scope.user = user;
            },
            true
        );

        $scope.logout = function () {
            userService.logout();
            $scope.user = undefined;
            $location.path("/");
        };
    };
    HeaderCtrl.$inject = ["$scope", "userService", "$location"];

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
