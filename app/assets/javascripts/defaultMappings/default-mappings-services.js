//===========================================================================
//    Copyright 2024 Delving B.V.
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

    var mod = angular.module("defaultMappings.services", ["narthex.common"]);

    mod.service("defaultMappingsService", [
        "$http", "$q", "playRoutes", "$location", "modalAlert",
        function ($http, $q, playRoutes, $location, modalAlert) {
            var app = playRoutes.controllers.AppController;

            var rejection = function (reply) {
                if (reply.status == 401) {
                    $location.path('/');
                }
                else {
                    console.log('error', reply);
                    if (reply.data && reply.data.problem) {
                        modalAlert.error("Processing Problem", "Error " + reply.status + ": " + reply.data.problem);
                    }
                    else {
                        modalAlert.error("Network Problem", reply.statusText);
                    }
                }
            };

            return {
                // Default Mappings API - Updated for named mappings structure

                // List all prefixes with their named mappings
                listDefaultMappings: function () {
                    return app.listDefaultMappings().get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },

                // List all named mappings for a specific prefix
                listMappingsForPrefix: function (prefix) {
                    return app.listMappingsForPrefix(prefix).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },

                // Get full info for a named mapping including all versions
                getNamedMappingInfo: function (prefix, name) {
                    return app.getNamedMappingInfo(prefix, name).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },

                // Get XML content for a specific version
                getDefaultMappingXml: function (prefix, name, version) {
                    return app.getDefaultMappingXml(prefix, name, version).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },

                // Create a new named mapping
                createNamedMapping: function (prefix, displayName) {
                    return app.createNamedMapping(prefix).post({displayName: displayName}).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },

                // Upload a new version to a named mapping
                uploadDefaultMapping: function (prefix, name, file, notes) {
                    var formData = new FormData();
                    formData.append('file', file);
                    if (notes) {
                        formData.append('notes', notes);
                    }

                    return $http.post('/narthex/app/default-mappings/' + prefix + '/' + name + '/upload', formData, {
                        transformRequest: angular.identity,
                        headers: {'Content-Type': undefined}
                    }).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },

                // Copy mapping from a dataset
                copyMappingFromDataset: function (prefix, name, spec, notes) {
                    return app.copyMappingFromDataset(prefix, name, spec).post({notes: notes}).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },

                // Set the current version for a named mapping
                setCurrentDefaultMapping: function (prefix, name, hash) {
                    return app.setCurrentDefaultMapping(prefix, name).post({hash: hash}).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },

                // Delete a specific version
                deleteDefaultMappingVersion: function (prefix, name, hash) {
                    return app.deleteDefaultMappingVersion(prefix, name, hash).delete().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },

                // Dataset Mapping Source API
                listDatasetMappingVersions: function (spec) {
                    return app.listDatasetMappingVersions(spec).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },

                getDatasetMappingXml: function (spec, version) {
                    return app.getDatasetMappingXml(spec, version).get().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },

                setDatasetMappingSource: function (spec, source, prefix, name, version) {
                    return app.setDatasetMappingSource(spec).post({
                        source: source,
                        prefix: prefix,
                        name: name,
                        version: version
                    }).then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                },

                rollbackDatasetMapping: function (spec, hash) {
                    return app.rollbackDatasetMapping(spec, hash).post().then(
                        function (response) {
                            return response.data;
                        },
                        rejection
                    );
                }
            };
        }
    ]);

    var handleRouteError = function ($rootScope, $location) {
        $rootScope.$on("$routeChangeError", function (e, next, current) {
            $location.path("/");
        });
    };
    handleRouteError.$inject = ["$rootScope", "$location"];
    mod.run(handleRouteError);
    return mod;
});
