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

  // -- RequireJS config --
  // Note: urlArgs must be a static string for r.js optimizer compatibility
  requirejs.config({
    // Increase timeout for slower connections/browsers (default is 7 seconds)
    waitSeconds: 30,
    // Cache busting: static version for r.js build (update when making releases)
    urlArgs: "v=0.8.2.20",
    // Packages = top-level folders; loads a contained file named "main.js"
    packages: [
      "common", "datasetList", "dataset", "skos", "terms", "categories", "defaultMappings"
    ],
    shim: {
      "jsRoutes": {
        deps: [],
        exports: "jsRoutes"
      },
      'jquery': {
        exports: '$'
      },
      'angular': {
        deps: ['jquery'],
        exports: 'angular'
      },
      'angular-route': {
        deps: ['angular'],
        exports: 'angular'
      },
      'angular-cookies': {
        deps: ['angular'],
        exports: 'angular'
      },
      'angular-file-upload': {
        deps: ['angular'],
        exports: 'angular'
      },
      'angular-sanitize': {
        deps: ['angular'],
        exports: 'angular'
      },
      'ngStorage': {
        deps: ['angular'],
        exports: 'angular'
      },
      'ng-grid': {
        deps: ['angular', 'jquery'],
        exports: 'ngGrid'
      },
      'bootstrap': {
        deps: ['jquery'],
        exports: '$.fn.tooltip'
      },
      'ui-bootstrap': {
        deps: ['angular'],
        exports: 'angular'
      },
      'ui-bootstrap-tpls': {
        deps: ['ui-bootstrap'],
        exports: 'angular'
      },
      'underscorejs': {
        exports: '_'
      }
    },
    paths: {
      // Relative paths work for both browser runtime and r.js build
      'requirejs': '../vendor/requirejs/require.min',
      'jquery': '../vendor/jquery/jquery.min',
      'angular': '../vendor/angularjs/angular.min',
      'angular-sanitize': '../vendor/angularjs/angular-sanitize.min',
      'angular-route': '../vendor/angularjs/angular-route.min',
      'angular-cookies': '../vendor/angularjs/angular-cookies.min',
      'ng-grid': '../vendor/ng-grid/ng-grid.min',
      'ngStorage': '../vendor/ngStorage/ngStorage.min',
      'angular-file-upload': '../vendor/angular-file-upload/angular-file-upload.min',
      'bootstrap': '../vendor/bootstrap/js/bootstrap.min',
      'ui-bootstrap': '../vendor/angular-ui-bootstrap/ui-bootstrap.min',
      'ui-bootstrap-tpls': '../vendor/angular-ui-bootstrap/ui-bootstrap-tpls.min',
      'underscorejs': '../vendor/underscorejs/underscore.min',
      'jsRoutes': '/narthex/jsRoutes'
    }
  });

  requirejs.onError = function (err) {
    console.log(err);
  };

  require(
    ['angular', 'angular-cookies', 'angular-route', 'angular-file-upload',
      'angular-sanitize', 'ngStorage', 'jquery',
      'underscorejs', 'bootstrap', 'ui-bootstrap', 'ui-bootstrap-tpls', 'ng-grid', './app'],
    function (angular) {
      angular.bootstrap(document, ['app']);
    }
  );

})(requirejs);
