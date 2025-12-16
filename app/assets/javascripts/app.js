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
 * The app module, as both AngularJS as well as RequireJS module.
 * Splitting an app in several Angular modules serves no real purpose in Angular 1.0/1.1.
 * (Hopefully this will change in the near future.)
 * Splitting it into several RequireJS modules allows async loading. We cannot take full advantage
 * of RequireJS and lazy-load stuff because the angular modules have their own dependency system.
 */

define(
    [
        "angular",
        "datasetList",
        "dataset",
        "skos",
        "terms",
        "categories",
        "defaultMappings",
        "stats",
        "discovery"
    ],
    function (angular) {
        "use strict";

        // We must already declare most dependencies here (except for common), or the submodules' routes
        // will not be resolved
        return angular.module(
            "app",
            [
                "narthex.datasetList",
                "narthex.dataset",
                "narthex.skos",
                "narthex.terms",
                "narthex.categories",
                "narthex.defaultMappings",
                "narthex.stats",
                "narthex.discovery",
                "angularFileUpload",
                "ngSanitize",
                "ui.bootstrap.tpls",
                "ui.bootstrap",
                "ngGrid"
            ]
        );
    }
);
