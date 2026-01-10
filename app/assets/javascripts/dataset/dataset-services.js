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
        "$http", "$q", "playRoutes", "modalAlert",
        function ($http, $q, playRoutes, modalAlert) {
            var app = playRoutes.controllers.AppController;

            var rejection = function (reply) {
                console.log('why', reply);
                if (reply.data.problem) {
                    modalAlert.error("Processing Problem", "Error " + reply.status + ": " + reply.data.problem);
                }
                else {
                    modalAlert.error("Network Problem", reply.statusText);
                }
            };

            return {
                command: function (spec, command) {
                    return app.command(spec, command).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
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
                toggleSkosifiedField: function (spec, payload) {
                    return app.toggleSkosifiedField(spec).post(payload).then(
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
                },
                // Source analysis methods
                sourceIndex: function (spec) {
                    return app.sourceIndex(spec).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                sourceNodeStatus: function (spec, path) {
                    return app.sourceNodeStatus(spec, path).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                sourceSample: function (spec, path, size) {
                    return app.sourceSample(spec, path, size).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                sourceHistogram: function (spec, path, size) {
                    return app.sourceHistogram(spec, path, size).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                // Quality summary methods
                qualitySummary: function (spec) {
                    return app.qualitySummary(spec).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                sourceQualitySummary: function (spec) {
                    return app.sourceQualitySummary(spec).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                qualityComparison: function (spec) {
                    return app.qualityComparison(spec).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                // Violation record lookup methods
                recordsByValue: function (spec, value, limit) {
                    return app.recordsByValue(spec, value, limit || 100).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                recordCountByValue: function (spec, value) {
                    return app.recordCountByValue(spec, value).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                exportProblemRecords: function (spec, value, violationType, format) {
                    return app.exportProblemRecords(spec, value, violationType || '', format || 'json').get().then(
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
    handleRouteError.$inject = ["$rootScope"];
    mod.run(handleRouteError);
    return mod;
});
