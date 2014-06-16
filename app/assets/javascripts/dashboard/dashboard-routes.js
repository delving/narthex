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

define(
    ["angular", "./dashboard-controllers", "common"],
    function (angular, controllers) {
        "use strict";

        var mod = angular.module("dashboard.routes", ["narthex.common"]);
        mod.config(
            [
                "$routeProvider", "userResolve",
                function ($routeProvider, userResolve) {
                    $routeProvider.when(
                        "/dashboard", {
                            templateUrl: "/assets/templates/dashboard.html",
                            controller: controllers.DashboardCtrl,
                            resolve: userResolve
                        }
                    ).when(
                        "/dataset/:fileName", {
                            templateUrl: "/assets/templates/dataset.html",
                            controller: controllers.FileDetailCtrl,
                            resolve: userResolve,
                            reloadOnSearch: false
                        }
                    );
                }
            ]
        );

        return mod;
    }
);
