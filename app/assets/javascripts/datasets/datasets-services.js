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

    var mod = angular.module("datasets.services", ["narthex.common"]);

    mod.service("datasetsService", [
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
                create: function (datasetName, prefix) {
                    return app.create(datasetName, prefix).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                command: function (datasetName, command) {
                    return app.command(datasetName, command).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                datasetInfo: function (datasetName) {
                    return app.datasetInfo(datasetName).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                datasetProgress: function (datasetName) {
                    return app.datasetProgress(datasetName).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                listDatasets: function () {
                    return app.listDatasets().get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                listPrefixes: function () {
                    return app.listPrefixes().get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                setMetadata: function (datasetName, metadata) {
                    return app.setMetadata(datasetName).post(metadata).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                setPublication: function (datasetName, publication) {
                    return app.setPublication(datasetName).post(publication).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                setCategories: function (datasetName, categories) {
                    return app.setCategories(datasetName).post(categories).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                harvest: function (datasetName, harvestInfo) {
                    return app.harvest(datasetName).post(harvestInfo).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                setHarvestCron: function (datasetName, harvestCron) {
                    return app.setHarvestCron(datasetName).post(harvestCron).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                listSipFiles: function (datasetName) {
                    return app.listSipFiles(datasetName).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                deleteLatestSipFile: function (datasetName) {
                    return app.deleteLatestSipFile(datasetName).get().then(
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
