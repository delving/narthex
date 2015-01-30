/**
 * Main, shows the start page and provides controllers for the header and the footer.
 * This the entry module which serves as an entry point so other modules only have to include a
 * single module.
 */
define(
    [
        "angular",
        "./dashboard-services",
        "./dashboard-controllers"
    ],
    function (angular, services, controllers) {
        "use strict";

        var loginRoutes = angular.module("dashboard.routes", ["narthex.common"]);
        loginRoutes.config(["$routeProvider", function ($routeProvider) {
            $routeProvider
                .when(
                "/",
                {
                    templateUrl: "/narthex/assets/templates/dashboard.html",
                    controller: controllers.DashboardCtrl
                }
            ).otherwise(
                {
                    templateUrl: "/narthex/assets/templates/notFound.html"
                }
            );
        }]);

        var narthexLogin = angular.module(
            "narthex.dashboard",
            [
                "ngCookies",
                "ngRoute",
                "ngStorage",
                "dashboard.routes",
                "dashboard.services"
            ]
        );
        narthexLogin.controller("IndexCtrl", controllers.IndexCtrl);
        return narthexLogin;
    }
);
