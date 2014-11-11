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

    var mod = angular.module("dashboard.services", ["narthex.common"]);

    mod.service("dashboardService", [
        "$http", "$q", "playRoutes", "$location",
        function ($http, $q, playRoutes, $location) {
            var dash = playRoutes.web.Dashboard;

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
                revert: function (datasetName, command) {
                    return dash.revert(datasetName, command).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                datasetInfo: function (datasetName) {
                    return dash.datasetInfo(datasetName).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                list: function () {
                    return dash.list().get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                setMetadata: function (datasetName, metadata) {
                    return dash.setMetadata(datasetName).post(metadata).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                setPublication: function (datasetName, publication) {
                    return dash.setPublication(datasetName).post(publication).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                setCategories: function (datasetName, categories) {
                    return dash.setCategories(datasetName).post(categories).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                harvest: function (datasetName, harvestInfo) {
                    return dash.harvest(datasetName).post(harvestInfo).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                setHarvestCron: function (datasetName, harvestCron) {
                    return dash.setHarvestCron(datasetName).post(harvestCron).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                analyze: function (datasetName) {
                    return dash.analyze(datasetName).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                saveRecords: function (datasetName) {
                    return dash.saveRecords(datasetName).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                listSipFiles: function () {
                    return dash.listSipFiles().get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },
                deleteSipFile: function (name) {
                    return dash.deleteSipFile(name).get().then(
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
