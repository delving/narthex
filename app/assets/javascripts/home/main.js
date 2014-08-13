/**
 * Main, shows the start page and provides controllers for the header and the footer.
 * This the entry module which serves as an entry point so other modules only have to include a
 * single module.
 */
define(
    [
        "angular",
        "./home-routes",
        "./home-services",
        "./home-controllers"
    ],
    function (angular, routes, services, controllers) {
        "use strict";

        var mod = angular.module(
            "narthex.home",
            [
                "ngCookies",
                "ngRoute",
                "home.routes",
                "home.services"
            ]
        );
        mod.controller("HeaderCtrl", controllers.HeaderCtrl);
        mod.controller("SidebarCtrl", controllers.SidebarCtrl);
        return mod;
    }
);
