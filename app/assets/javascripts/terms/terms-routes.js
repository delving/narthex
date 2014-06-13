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
        "./terms-controllers",
        "./terms-services",
        "common"
    ],
    function (angular, controllers) {
        "use strict";

        var mod = angular.module("terms.routes", ["narthex.common"]);
        mod.config(["$routeProvider", function ($routeProvider) {
            $routeProvider.when(
                "/terms/:fileName",
                {
                    templateUrl: "/assets/templates/terms.html",
                    controller: controllers.TermsCtrl
                }
            )
        }]);
        return mod;
    }
);