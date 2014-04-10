/**
 * User service, exposes user model to the rest of the app.
 */
define(["angular", "common"], function (angular) {
    "use strict";

    var mod = angular.module("dashboard.services", ["xml-ray.common"]);

    mod.service("dashboardService", ["$http", "$q", "playRoutes", function ($http, $q, playRoutes) {
        var user, token;
        return {
            list: function () {
                return playRoutes.controllers.Dashboard.list().get().then(function (response) {
                    return response.data;
                });
            },
            status: function(fileName) {
                return playRoutes.controllers.Dashboard.status(fileName).get().then(function(response) {
                    return response.data;
                });
            },
            analysis: function(fileName) {
                return playRoutes.controllers.Dashboard.analysis(fileName).get().then(function(response) {
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
