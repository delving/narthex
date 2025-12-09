//===========================================================================
//    Copyright 2024 Delving B.V.
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
        "./default-mappings-controllers",
        "./default-mappings-services"
    ],
    function (angular, controllers, services) {
        "use strict";

        var defaultMappingsRoutes = angular.module("defaultMappings.routes", ["narthex.common"]);
        defaultMappingsRoutes.config(
            [
                "$routeProvider",
                function ($routeProvider) {
                    $routeProvider.when(
                        "/default-mappings", {
                            templateUrl: "/narthex/assets/templates/default-mappings-list.html",
                            controller: controllers.DefaultMappingsListCtrl,
                            reloadOnSearch: false
                        }
                    );
                }
            ]
        );

        var narthexDefaultMappings = angular.module("narthex.defaultMappings", [
            "ngRoute",
            "defaultMappings.routes",
            "defaultMappings.services",
            "datasetList.services",
            "narthex.common"
        ]);

        narthexDefaultMappings.controller('DefaultMappingsListCtrl', controllers.DefaultMappingsListCtrl);
        narthexDefaultMappings.controller('XmlPreviewModalCtrl', controllers.XmlPreviewModalCtrl);
        narthexDefaultMappings.controller('XmlDiffModalCtrl', controllers.XmlDiffModalCtrl);

        return narthexDefaultMappings;
    }
);
