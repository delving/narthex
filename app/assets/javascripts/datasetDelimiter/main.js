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
 * Dataset Delimiter module - simplified view for setting record root and unique ID
 * during the delimiter configuration step after raw analysis.
 */
define(
    [
        "angular",
        "./dataset-delimiter-controllers",
        "../dataset/dataset-services"
    ],
    function (angular, controllers) {
        "use strict";

        var delimiterRoutes = angular.module("datasetDelimiter.routes", ["narthex.common"]);
        delimiterRoutes.config(
            [
                "$routeProvider",
                function ($routeProvider) {
                    $routeProvider.when(
                        "/dataset-delimiter/:spec", {
                            templateUrl: "/narthex/assets/templates/dataset-delimiter.html",
                            controller: controllers.DatasetDelimiterCtrl,
                            reloadOnSearch: false
                        }
                    );
                }
            ]
        );

        var narthexDatasetDelimiter = angular.module("narthex.datasetDelimiter", [
            "ngRoute",
            "datasetDelimiter.routes",
            "dataset.services",
            "narthex.common"
        ]);

        narthexDatasetDelimiter.controller('DelimiterTreeCtrl', controllers.DelimiterTreeCtrl);
        narthexDatasetDelimiter.controller('DelimiterNodeCtrl', controllers.DelimiterNodeCtrl);

        return narthexDatasetDelimiter;
    });
