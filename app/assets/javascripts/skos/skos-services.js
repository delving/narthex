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

define(["angular", "common"], function (angular) {
    "use strict";

    var mod = angular.module("skos.services", ["narthex.common"]);

    mod.service("skosService", [
        "$http", "$q", "playRoutes", "$location",
        function ($http, $q, playRoutes, $location) {
            var app = playRoutes.web.AppController;

            var rejection = function (reply) {
                if (reply.status == 401) { // unauthorized
                    $location.path('/');
                }
                else {
                    console.log('why', reply);
                    if (reply.data.problem) {
                        alert("Processing problem " + reply.status + " (" + reply.data.problem + ")");
                    }
                    else {
                        alert("Network problem " + reply.statusText);
                    }
                }
            };

            return {
                listSkos: function () {
                    return app.listSkos().get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                createSkos: function (spec) {
                    return app.createSkos(spec).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                skosInfo: function (spec) {
                    return app.skosInfo(spec).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                skosStatistics: function (spec) {
                    return app.skosStatistics(spec).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                setProperties: function (spec, payload) {
                    return app.setSkosProperties(spec).post(payload).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                searchConceptScheme: function (conceptSchemeName, sought) {
                    return app.searchConceptScheme(conceptSchemeName, sought).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                getThesaurusMappings: function (schemeNameA, schemeNameB) {
                    return app.getThesaurusMappings(schemeNameA, schemeNameB).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                setThesaurusMapping: function (schemeNameA, schemeNameB, body) {
                    return app.setThesaurusMapping(schemeNameA, schemeNameB).post(body).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                }
            };
        }
    ]);

    /**
     * If the current route does not resolve, go back to the start page.
     */
    var handleRouteError = function ($rootScope, $location) {
        $rootScope.$on("$routeChangeError", function (e, next, current) {
            $location.path("/");
        });
    };
    handleRouteError.$inject = ["$rootScope", "$location"];
    mod.run(handleRouteError);
    return mod;
});
