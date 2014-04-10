/**
 * User service, exposes user model to the rest of the app.
 */
define(["angular", "common"], function (angular) {
    "use strict";

    var mod = angular.module("dashboard.services", ["xml-ray.common"]);

    mod.service("dashboardService", ["$http", "$q", "playRoutes", function ($http, $q, playRoutes) {
        var user, token;
        return {
            listFiles: function () {
                return playRoutes.controllers.FileHandling.list().get().then(function (response) {
                    return response.data;
                });
            },
            analyzeFile: function(fileName) {
                return playRoutes.controllers.FileHandling.analyze(fileName).get().then(function(response) {
                    return response.data;
                });
            },
            statusFile: function(fileName) {
                return playRoutes.controllers.FileHandling.status(fileName).get().then(function(response) {
                    return response.data;
                });
            }
        };
    }]);

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
