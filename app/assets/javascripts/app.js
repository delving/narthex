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
        "home",
        "user",
        "dashboard",
        "angular-file-upload"
    ],
    function (angular) {
        "use strict";

// todo: get this workaround working or wait for reply
// https://github.com/webjars/angularjs-nvd3-directives/issues/1
//        require.config({
//            'paths': { d3js: webjars.path("d3js", "d3") },
//            'shim': { d3: {
//                'deps': [ 'angular']
//            } }
//        });
//
//        require(['d3js'], function (d3js) {
//            console.log("d3js", d3js);
//        });
//
//        require.config({
//            'paths': { nvd3: webjars.path("nvd3", "nv.d3") },
//            'shim': { 'nv.d3': ['d3js'] }
//        });
//
//        require(['nvd3'], function (nvd3) {
//            console.log("nvd3", nvd3);
//        });
//
//        require.config({
//            'paths': { 'angularjs-nvd3-directives': webjars.path("angularjs-nvd3-directives", "angularjs-nvd3-directives") },
//            'shim': { 'angularjs-nvd3-directives': [ 'd3js', 'nvd3'] }
//        });
//
//        require(['angularjs-nvd3-directives'], function (directives) {
//            console.log("This should define directives, not be undefined:", directives);
//        });
//
        // We must already declare most dependencies here (except for common), or the submodules' routes
        // will not be resolved
        return angular.module(
            "app",
            [
                "xml-ray.home",
                "xml-ray.user",
                "xml-ray.dashboard",
                "angularFileUpload"
            ]
        );
    }
);
