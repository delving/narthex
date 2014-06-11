/**
 * Main, shows the start page and provides controllers for the header and the footer.
 * This the entry module which serves as an entry point so other modules only have to include a
 * single module.
 */
define(
    [
        "angular",
        "./terms-routes",
        "./terms-services",
        "./terms-controllers"
    ],
    function (angular, routes, services, controllers) {
        "use strict";

        var mod = angular.module(
            "narthex.terms",
            [
                "ngCookies",
                "ngRoute",
                "terms.routes",
                "terms.services"
            ]
        );
//        mod.controller("FooterCtrl", controllers.FooterCtrl);
        return mod;
    }
);
