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

    var mod = angular.module("discovery.services", ["narthex.common"]);

    mod.service("discoveryService", [
        "$http", "$q",
        function ($http, $q) {

            var baseUrl = '/narthex/app/discovery';

            return {
                // Sources CRUD
                listSources: function() {
                    return $http.get(baseUrl + '/sources').then(function(r) { return r.data; });
                },

                getSource: function(id) {
                    return $http.get(baseUrl + '/sources/' + id).then(function(r) { return r.data; });
                },

                createSource: function(source) {
                    return $http.post(baseUrl + '/sources', source).then(function(r) { return r.data; });
                },

                updateSource: function(id, source) {
                    return $http.put(baseUrl + '/sources/' + id, source).then(function(r) { return r.data; });
                },

                deleteSource: function(id) {
                    return $http.delete(baseUrl + '/sources/' + id).then(function(r) { return r.data; });
                },

                // Discovery
                discoverSets: function(sourceId) {
                    return $http.get(baseUrl + '/sources/' + sourceId + '/discover').then(function(r) { return r.data; });
                },

                // Ignore management
                ignoreSets: function(sourceId, setSpecs) {
                    return $http.post(baseUrl + '/sources/' + sourceId + '/ignore', { setSpecs: setSpecs }).then(function(r) { return r.data; });
                },

                unignoreSets: function(sourceId, setSpecs) {
                    return $http.post(baseUrl + '/sources/' + sourceId + '/unignore', { setSpecs: setSpecs }).then(function(r) { return r.data; });
                },

                // Import
                importSets: function(requests) {
                    return $http.post(baseUrl + '/import', requests).then(function(r) { return r.data; });
                },

                // Utility
                previewSpecTransform: function(setSpec) {
                    return $http.post(baseUrl + '/preview-spec', { setSpec: setSpec }).then(function(r) { return r.data; });
                }
            };
        }
    ]);

    return mod;
});
