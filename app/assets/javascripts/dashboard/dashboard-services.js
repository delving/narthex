/**
 * User service, exposes user model to the rest of the app.
 */
define(["angular", "common"], function (angular) {
    "use strict";

    var mod = angular.module("dashboard.services", ["xml-ray.common"]);

    mod.service("dashboardService", ["$http", "$q", "playRoutes", "$location", function ($http, $q, playRoutes, $location) {
        var dash = playRoutes.controllers.Dashboard;

        var rejection = function (why) {
            if (why.status == 401) {
                $location.path('/login');
            }
            else {
                console.log('why', why);
                alert("HTTP Problem code +" + why.status);
            }
        };

        return {
            list: function () {
                return dash.list().get().then(
                    function (response) {
                        return response.data;
                    },
                    rejection
                );
            },
            status: function (fileName) {
                return dash.status(fileName).get().then(
                    function (response) {
                        return response.data;
                    },
                    rejection
                );
            },
            index: function (fileName) {
                return dash.index(fileName).get().then(
                    function (response) {
                        return response.data;
                    },
                    rejection
                );
            },
            nodeStatus: function (fileName, path) {
                return dash.nodeStatus(fileName, path).get().then(
                    function (response) {
                        return response.data;
                    },
                    rejection
                );
            },
            sample: function (fileName, path, size) {
                return dash.sample(fileName, path, size).get().then(
                    function (response) {
                        return response.data;
                    },
                    rejection
                );
            },
            histogram: function (fileName, path, size) {
                return dash.histogram(fileName, path, size).get().then(
                    function (response) {
                        return response.data;
                    },
                    rejection
                );
            },
            uniqueText: function (fileName, path) {
                return dash.uniqueText(fileName, path).get().then(
                    function (response) {
                        return response.data;
                    },
                    rejection);
            },
            histogramText: function (fileName, path) {
                return dash.histogramText(fileName, path, size).get().then(
                    function (response) {
                        return response.data;
                    },
                    rejection
                );
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
