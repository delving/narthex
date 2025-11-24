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
      "common", "datasetList", "dataset", "skos", "terms", "categories"
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
      'ngStorage': ['angular'],
      'ng-grid': ['angular'],
      'bootstrap': ['jquery'],
      'ui-bootstrap': ['angular'],
      'ui-bootstrap-tpls': ['ui-bootstrap']
    },
    paths: {
      // Static files first (public/vendor/), WebJars fallback (lib/)
      'requirejs': ['/narthex/assets/vendor/requirejs/require.min', '../lib/requirejs/require'],
      'jquery': ['/narthex/assets/vendor/jquery/jquery.min', '/narthex/assets/vendor/jquery/jquery', '../lib/jquery/jquery'],
      'angular': ['/narthex/assets/vendor/angularjs/angular.min', '/narthex/assets/vendor/angularjs/angular', '../lib/angularjs/angular'],
      'angular-sanitize': ['/narthex/assets/vendor/angularjs/angular-sanitize.min', '../lib/angularjs/angular-sanitize'],
      'angular-route': ['/narthex/assets/vendor/angularjs/angular-route.min', '../lib/angularjs/angular-route'],
      'angular-cookies': ['/narthex/assets/vendor/angularjs/angular-cookies.min', '../lib/angularjs/angular-cookies'],
      'ng-grid': ['/narthex/assets/vendor/ng-grid/ng-grid.min', '../lib/ng-grid/ng-grid'],
      'ngStorage': ['/narthex/assets/vendor/ngStorage/ngStorage.min', '../lib/ngStorage/ngStorage'],
      'angular-file-upload': ['/narthex/assets/vendor/angular-file-upload/angular-file-upload.min', '../lib/angular-file-upload/angular-file-upload'],
      'bootstrap': ['/narthex/assets/vendor/bootstrap/js/bootstrap.min', '../lib/bootstrap/js/bootstrap'],
      'ui-bootstrap': ['/narthex/assets/vendor/angular-ui-bootstrap/ui-bootstrap.min', '../lib/angular-ui-bootstrap/ui-bootstrap'],
      'ui-bootstrap-tpls': ['/narthex/assets/vendor/angular-ui-bootstrap/ui-bootstrap-tpls.min', '../lib/angular-ui-bootstrap/ui-bootstrap-tpls'],
      'underscorejs': ['/narthex/assets/vendor/underscorejs/underscore.min', '../lib/underscorejs/underscore'],
      'jsRoutes': ['/narthex/jsRoutes']
    }
  });

  requirejs.onError = function (err) {
    console.log(err);
  };

  require(
    ['angular', 'angular-cookies', 'angular-route', 'angular-file-upload',
      'angular-sanitize', 'ngStorage', 'jquery',
      'underscorejs', 'bootstrap', 'ui-bootstrap-tpls', 'ui-bootstrap', 'ng-grid', './app'],
    function (angular) {
      angular.bootstrap(document, ['app']);
    }
  );

})(requirejs);
