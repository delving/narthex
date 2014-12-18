//===========================================================================
//    Copyright 2014 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================

define(["angular"], function (angular) {
    "use strict";

    var USERNAME_COOKIE = "NARTHEX-User";

    /** Controls the index page */
    var HomeCtrl = function ($scope, $rootScope, $cookies, $location, userService, $timeout) {

        $scope.credentials = {
            username: $cookies[USERNAME_COOKIE]
        };
        $scope.login = function (credentials) {
            userService.loginUser(credentials).then(
                function (response) {
                    if (response.profile) {
                        $cookies[USERNAME_COOKIE] = credentials.username;
                        $location.path("/datasets");
                    }
                    else {
                        $scope.credentials.password = "";
                        $scope.errorMessage = response.problem;
                    }
                }
            );
        };

        if (userService.getUser()) {
            if (userService.checkLogin()) {
                $location.path("/datasets")
            }
        }
    };
    HomeCtrl.$inject = ["$scope", "$rootScope", "$cookies", "$location", "userService", "$timeout"];

    /** Controls the header */
    var HeaderCtrl = function ($rootScope, $scope, userService, $location) {

        $scope.setOrg = function (orgId) {
            $rootScope.orgId = orgId;
        };

        $scope.toggleSidebar = function() {
            $scope.toggle = !$scope.toggle;
            $cookieStore.put('toggle', $scope.toggle);
        };

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
            userService.logout().then(function () {
                $scope.user = undefined;
                $location.path("/");
            }, function (why) {
                console.log("unable to logout", why);
            });
        };

        $rootScope.breadcrumbs = {};

    };
    HeaderCtrl.$inject = ["$rootScope", "$scope", "userService", "$location"];


    return {
        HeaderCtrl: HeaderCtrl,
        HomeCtrl: HomeCtrl
    };

});
