//===========================================================================
//    Copyright 2026 Delving B.V.
//===========================================================================

define(["angular", "common"], function (angular) {
    "use strict";

    var mod = angular.module("recdefs.services", ["narthex.common"]);

    mod.service("recDefsService", [
        "$http", "$q", "playRoutes", "$location", "modalAlert",
        function ($http, $q, playRoutes, $location, modalAlert) {
            var app = playRoutes.controllers.AppController;

            var rejection = function (reply) {
                if (reply.status == 401) {
                    $location.path('/');
                } else {
                    console.log('error', reply);
                    if (reply.data && reply.data.problem) {
                        modalAlert.error("Rec-Def Problem", "Error " + reply.status + ": " + reply.data.problem);
                    } else {
                        modalAlert.error("Network Problem", reply.statusText);
                    }
                }
            };

            return {
                listRecDefs: function () {
                    return app.listRecDefs().get().then(function (r) { return r.data; }, rejection);
                },

                listRecDefVersions: function (prefix) {
                    return app.listRecDefVersions(prefix).get().then(function (r) { return r.data; }, rejection);
                },

                uploadRecDef: function (prefix, recDefFile, xsdFile, notes) {
                    var formData = new FormData();
                    formData.append('recdef', recDefFile);
                    if (xsdFile) {
                        formData.append('xsd', xsdFile);
                    }
                    if (notes) {
                        formData.append('notes', notes);
                    }
                    return $http.post('/narthex/app/rec-defs/' + prefix + '/upload', formData, {
                        transformRequest: angular.identity,
                        headers: {'Content-Type': undefined}
                    }).then(function (r) { return r.data; }, rejection);
                },

                setCurrentRecDef: function (prefix, hash) {
                    return app.setCurrentRecDef(prefix).post({hash: hash}).then(function (r) { return r.data; }, rejection);
                },

                deleteRecDefVersion: function (prefix, hash) {
                    return app.deleteRecDefVersion(prefix, hash).delete().then(function (r) { return r.data; }, rejection);
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
