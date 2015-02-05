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
                listVocabularies: function () {
                    return app.listVocabularies().get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                createVocabulary: function (spec) {
                    return app.createVocabulary(spec).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                vocabularyInfo: function (spec) {
                    return app.vocabularyInfo(spec).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                vocabularyStatistics: function (spec) {
                    return app.vocabularyStatistics(spec).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                setVocabularyProperties: function (spec, payload) {
                    return app.setVocabularyProperties(spec).post(payload).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                getMappings: function (specA, specB) {
                    return app.getSkosMappings(specA, specB).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                searchVocabluary: function (spec, sought) {
                    return app.searchVocabulary(spec, sought).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                toggleMapping: function (specA, specB, body) {
                    return app.toggleSkosMapping(specA, specB).post(body).then(
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
