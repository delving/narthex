/**
 * Home controllers.
 */
define(["angular"], function (angular) {
    "use strict";

    /** Controls the index page */
    var HomeCtrl = function ($scope, $rootScope, $cookies, $location, userService) {
        $scope.token = $cookies["XSRF-TOKEN"];
        if ($scope.token) {
            userService.checkLogin().then(function(response) {
                $location.path("/dashboard")
            }, function(reason) {
                $scope.token = "no token";
                $cookies["XSRF-TOKEN"] = undefined;
            });
        }
    };
    HomeCtrl.$inject = ["$scope", "$rootScope", "$cookies", "$location", "userService"];

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
            userService.logout().then(function() {
                $scope.user = undefined;
                $location.path("/");
            }, function(why) {
                console.log("unable to logout", why);
            });
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
