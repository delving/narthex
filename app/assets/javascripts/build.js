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

/*
 * A custom build profile that is passed to the optimizer via requireJsShim in build.sbt.
 * Play does this via settings it as the mainConfigFile:
 * http://requirejs.org/docs/optimization.html#mainConfigFile
 */
requirejs.config({
    packages: [
        "common",
        "dashboard",
        "datasetList",
        "dataset",
        "skos",
        "terms",
        "categories"
    ],
    paths: {
        // Make the optimizer ignore CDN assets
        "_": "empty:",
        "jquery": "empty:",
        "bootstrap": "empty:",
        "angular": "empty:",
        "angular-cookies": "empty:",
        "angular-route": "empty:",
        // empty: so the optimizer doesn't try to find jsRoutes.js in our project
        "jsRoutes": "empty:"
    }
});
