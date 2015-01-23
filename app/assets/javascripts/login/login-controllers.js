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
    var LoginCtrl = function ($scope, $rootScope, $cookies, $location, userService, $timeout) {

        $scope.credentials = {
            username: $cookies[USERNAME_COOKIE]
        };
        userService.checkLogin().then(function (user) {
            $scope.editedUser = angular.copy(user);
        });

        $scope.login = function (credentials) {
            console.log("Login", $scope.credentials);
            $scope.errorMessage = undefined;
            userService.loginUser(credentials).then(
                function (response) {
                    if (response.profile) {
                        userService.checkLogin().then(function (user) {
                            $scope.editedUser = angular.copy(user);
                        });
                        $cookies[USERNAME_COOKIE] = credentials.username;
                    }
                    else {
                        $scope.credentials.password = "";
                        $scope.errorMessage = response.problem;
                    }
                }
            );
        };

        function compareUserToEdited() {
            return $scope.unchangedUser = angular.equals($scope.user, $scope.editedUser);
        }

        $scope.$watch("editedUser", compareUserToEdited, true);

        $scope.setProfile = function (editedUser) {
            userService.setProfile(editedUser).then(function(user) {
                $scope.user = user;
//                $scope.editedUser = angular.copy(user);
//                console.log('set profile returned', user, $scope.editedUser);
                compareUserToEdited()
            });
        };

    };
    LoginCtrl.$inject = ["$scope", "$rootScope", "$cookies", "$location", "userService", "$timeout"];

    /** Controls the header */
    var IndexCtrl = function ($rootScope, $scope, userService, $location) {

        $scope.initialize = function (orgId, sipCreatorLink) {
            console.log("Initializing index");
            $rootScope.orgId = orgId;
            $rootScope.sipCreatorLink = sipCreatorLink;
            $scope.toggleBar = true;
        };

        $scope.homePage = function () {
            $location.path('/');
        };

        $scope.datasetsPage = function () {
            $location.path('/datasets');
        };

        $scope.categoriesPage = function () {
            $location.path('/categories');
        };

        $scope.thesaurusPage = function () {
            $location.path('/thesaurus');
        };

        $scope.breadcrumbsPage = function() {
            $location.path('/dataset/' + $rootScope.breadcrumbs.dataset)
        };

        $scope.toggleSidebar = function () {
            $scope.toggleBar = !$scope.toggleBar;
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
                $scope.homePage();
            }, function (why) {
                console.log("unable to logout", why);
            });
        };

        $rootScope.breadcrumbs = {};

    };

    IndexCtrl.$inject = ["$rootScope", "$scope", "userService", "$location"];

    return {
        IndexCtrl: IndexCtrl,
        LoginCtrl: LoginCtrl
    };

});
