/**
 * Home controllers.
 */
define(["angular"], function (angular) {
    "use strict";

    /** Controls the index page */
    var HomeCtrl = function ($scope, $rootScope) {
        $rootScope.pageTitle = "Welcome";
    };
    HomeCtrl.$inject = ["$scope", "$rootScope"];

    /** Controls the header */
    var HeaderCtrl = function ($scope, userService, helper, $location) {
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
