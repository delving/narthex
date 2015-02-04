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

(function (requirejs) {
    "use strict";

    // -- DEV RequireJS config --
    requirejs.config({
        // Packages = top-level folders; loads a contained file named "main.js"
        packages: [
            "common",
            "dashboard",
            "datasetList",
            "dataset",
            "skos",
            "terms",
            "categories"
        ],
        shim: {
            "jsRoutes": {
                deps: [],
                // it's not a RequireJS module, so we have to tell it what var is returned
                exports: "jsRoutes"
            }
        },
        paths: {
            "jsRoutes": "/narthex/jsroutes"
        }
    });

    requirejs.onError = function (err) {
        console.log(err);
    };

    require(['angular-file-upload-shim'], function() {
        require(
            [
                "angular",
                "angular-cookies",
                "angular-route",
                "angular-file-upload",
                "angularjs-nvd3-directives",
//                "angular-pusher",
                "ngStorage",
                "jquery",
                "underscorejs",
                "bootstrap",
                "ui-bootstrap-tpls",
                "ui-bootstrap",
                "ng-grid",
                "./app"
            ],
            function (angular) {
                angular.bootstrap(document, ["app"]);
            }
        );
    });

})(requirejs);
