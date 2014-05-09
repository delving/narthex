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
    [
        "angular",
        "./home-controllers",
        "./home-services",
        "common"
    ],
    function (angular, controllers) {
        "use strict";

        var mod = angular.module("home.routes", ["narthex.common"]);
        mod.config(["$routeProvider", function ($routeProvider) {
            $routeProvider
                .when("/", {templateUrl: "/assets/templates/home/home.html", controller: controllers.HomeCtrl})
                .otherwise({templateUrl: "/assets/templates/home/notFound.html"});
        }]);
        return mod;
    }
);
