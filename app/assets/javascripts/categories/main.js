/**
 * Main, shows the start page and provides controllers for the header and the footer.
 * This the entry module which serves as an entry point so other modules only have to include a
 * single module.
 */
define(
    [
        "angular",
        "./categories-routes",
        "./categories-services",
        "./categories-controllers"
    ],
    function (angular, routes, services) {
        "use strict";

        return angular.module(
            "narthex.categories",
            [
                "ngCookies",
                "ngRoute",
                "categories.routes",
                "categories.services"
            ]
        );
    }
);
