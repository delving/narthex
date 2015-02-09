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

    var mod = angular.module("dataset.services", ["narthex.common"]);

    mod.service("datasetService", [
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
                datasetInfo: function (spec) {
                    return app.datasetInfo(spec).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                index: function (spec) {
                    return app.index(spec).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                getCategorySourcePaths: function (spec) {
                    return app.getCategorySourcePaths(spec).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                nodeStatus: function (spec, path) {
                    return app.nodeStatus(spec, path).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                setRecordDelimiter: function (spec, body) {
                    return app.setRecordDelimiter(spec).post(body).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                setSkosField: function (spec, payload) {
                    return app.setSkosField(spec).post(payload).then(
                        function (response) {
                            return response.data.action;
                        },
                        rejection
                    );
                },
                sample: function (spec, path, size) {
                    return app.sample(spec, path, size).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                histogram: function (spec, path, size) {
                    return app.histogram(spec, path, size).get().then(
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
