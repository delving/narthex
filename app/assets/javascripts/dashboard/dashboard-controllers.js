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

        $scope.login = function () {
            $scope.errorMessage = undefined;
            $cookies[USERNAME_COOKIE] = $scope.credentials.username;
        };

        checkLogin();


    };
    DashboardCtrl.$inject = ["$scope", "$rootScope", "$cookies", "$window", "userService", "$timeout"];



    return {
        DashboardCtrl: DashboardCtrl
    };

});
