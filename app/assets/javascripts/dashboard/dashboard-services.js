/**
 * User service, exposes user model to the rest of the app.
 */
define(["angular", "common"], function (angular) {
    "use strict";

    var mod = angular.module("dashboard.services", ["xml-ray.common"]);

    mod.service("dashboardService", ["$http", "$q", "playRoutes", function ($http, $q, playRoutes) {
        var dash = playRoutes.controllers.Dashboard;
        return {
            list: function () {
                return dash.list().get().then(function (response) {
                    return response.data;
                });
            },
            status: function (fileName) {
                return dash.status(fileName).get().then(function (response) {
                    return response.data;
                });
            },
            index: function (fileName) {
                return dash.index(fileName).get().then(function (response) {
                    return response.data;
                });
            },
            nodeStatus: function (fileName, path) {
                return dash.nodeStatus(fileName, path).get().then(function (response) {
                    return response.data;
                });
            },
            sample: function (fileName, path, size) {
                return dash.sample(fileName, path, size).get().then(function (response) {
                    return response.data;
                });
            },
            histogram: function (fileName, path, size) {
                return dash.histogram(fileName, path, size).get().then(function (response) {
                    return response.data;
                });
            },
            uniqueText: function (fileName, path) {
                return dash.uniqueText(fileName, path).get().then(function (response) {
                    return response.data;
                });
            },
            histogramText: function (fileName, path) {
                return dash.histogramText(fileName, path, size).get().then(function (response) {
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
