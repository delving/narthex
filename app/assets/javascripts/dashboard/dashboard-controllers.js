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
    var DashboardCtrl = function ($scope, $rootScope, $cookies, $window, userService, $timeout) {

        $scope.credentials = { };
        $scope.credentials.username = $cookies[USERNAME_COOKIE];
        $scope.newActor = {};
        $scope.change = {};
        $scope.changeDisabled = true;


        function checkLogin() {
            userService.checkLogin().then(function (user) {
                $scope.editedUser = angular.copy(user);
            });
        }

        function listActors() {
            if (userService.getUser()) {
                userService.listActors().then(function (actorList) {
                    $scope.actorList = actorList;
                });
            }
        }

        $scope.login = function () {
            $scope.errorMessage = undefined;
            $cookies[USERNAME_COOKIE] = $scope.credentials.username;
            listActors();
        };

        checkLogin();

        function compareUserToEdited() {
            return $scope.unchangedUser = angular.equals($scope.user, $scope.editedUser);
        }

        $scope.$watch("editedUser", compareUserToEdited, true);



    };
    DashboardCtrl.$inject = ["$scope", "$rootScope", "$cookies", "$window", "userService", "$timeout"];

    /** Controls the sidebar and headers */
    var IndexCtrl = function ($rootScope, $scope, userService, $location) {

        $scope.initialize = function (orgId, sipCreatorLink) {
            //console.log("Initializing index");
            $rootScope.orgId = orgId;
            $rootScope.sipCreatorLink = sipCreatorLink;
            $scope.toggleBar = true;
        };

        $rootScope.sidebarNav = function (page, dataset) {

            var navlist = $('#sidebar-nav a');
            navlist.removeClass('active');
            console.log(page);
            switch (page) {
                case 'homepage':
                    $location.path('/');
                    break;
                case 'dataset-list':
                    if(dataset){
                        $location.search('dataset', dataset).hash(dataset).path('/dataset-list');
                    }
                    else {
                        $location.path('/dataset-list');
                    }
                    break;
                case 'skos':
                    $location.path('/skos');
                    break;
                case 'categories':
                    $location.path('/categories');
                    break;
                case 'thesaurus':
                    $location.path('/thesaurus');
                    break;
                default:
                    $location.path('/');
            }
            $('#nav-' + page).addClass('active');

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
                $scope.sidebarNav("homepage");
            }, function (why) {
                console.log("unable to logout", why);
            });
        };

    };

    IndexCtrl.$inject = ["$rootScope", "$scope", "userService", "$location"];

    return {
        IndexCtrl: IndexCtrl,
        DashboardCtrl: DashboardCtrl
    };

});
