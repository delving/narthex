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

/**
 * Discovery module - OAI-PMH dataset discovery and import.
 */
define(
    [
        "angular",
        "./discovery-controllers",
        "./discovery-services"
    ],
    function (angular, controllers, services) {
        "use strict";

        var discoveryRoutes = angular.module("discovery.routes", ["narthex.common"]);
        discoveryRoutes.config([
            "$routeProvider",
            function ($routeProvider) {
                $routeProvider.when(
                    "/discovery",
                    {
                        templateUrl: "/narthex/assets/templates/discovery.html",
                        controller: controllers.DiscoveryCtrl,
                        reloadOnSearch: false
                    }
                );
            }
        ]);

        var narthexDiscovery = angular.module("narthex.discovery", [
            "ngRoute",
            "discovery.routes",
            "discovery.services",
            "narthex.common",
            "narthex.defaultMappings"
        ]);

        narthexDiscovery.controller('DiscoveryCtrl', controllers.DiscoveryCtrl);
        narthexDiscovery.controller('SourceConfigModalCtrl', controllers.SourceConfigModalCtrl);
        narthexDiscovery.controller('ImportConfirmModalCtrl', controllers.ImportConfirmModalCtrl);

        return narthexDiscovery;
    }
);
