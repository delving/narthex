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
      "common", "dashboard", "datasetList", "dataset", "skos", "terms", "categories"
    ],
    shim: {
      "jsRoutes": {
        deps: [],
        // it's not a RequireJS module, so we have to tell it what var is returned
        exports: "jsRoutes"
      },
      // Hopefully this all will not be necessary but can be fetched from WebJars in the future
      'angular': {
        deps: ['jquery'],
        exports: 'angular'
      },
      'angular-route': ['angular'],
      'angular-cookies': ['angular'],
      'angular-file-upload': ['angular'],
      'angular-sanitize': ['angular'],
      'angularjs-nvd3-directives': ['angular'],
      'ngStorage': ['angular'],
      'ng-grid': ['angular'],
      'bootstrap': ['jquery'],
      'ui-bootstrap': ['angular'],
      'ui-bootstrap-tpls': ['ui-bootstrap']
    },
    paths: {
      'requirejs': ['../lib/requirejs/require'],
      'jquery': ['../lib/jquery/jquery'],
      'angular': ['../lib/angularjs/angular'],
      'angular-sanitize': ['../lib/angularjs/angular-sanitize'],
      'ng-grid': ['../lib/ng-grid/ng-grid'],
      'ngStorage': ['../lib/ngStorage/ngStorage'],
      'angular-route': ['../lib/angularjs/angular-route'],
      'angular-cookies': ['../lib/angularjs/angular-cookies'],
      'angular-file-upload': ['../lib/angular-file-upload/angular-file-upload'],
      'angularjs-nvd3-directives': ['../lib/angularjs-nvd3-directives/angularjs-nvd3-directives'],
      'bootstrap': ['../lib/bootstrap/js/bootstrap'],
      'ui-bootstrap': ['../lib/angular-ui-bootstrap/ui-bootstrap'],
      'ui-bootstrap-tpls': ['../lib/angular-ui-bootstrap/ui-bootstrap-tpls'],
      'underscorejs': ['../lib/underscorejs/underscore'],
      'jsRoutes': ['/narthex/jsRoutes']
    }
  });

  requirejs.onError = function (err) {
    console.log(err);
  };

  require(
    ['angular', 'angular-cookies', 'angular-route', 'angular-file-upload',
      'angularjs-nvd3-directives', 'angular-sanitize', 'ngStorage', 'jquery',
      'underscorejs', 'bootstrap', 'ui-bootstrap-tpls', 'ui-bootstrap', 'ng-grid', './app'],
    function (angular) {
      angular.bootstrap(document, ['app']);
    }
  );

})(requirejs);
