/**
 * Main, shows the start page and provides controllers for the header and the footer.
 * This the entry module which serves as an entry point so other modules only have to include a
 * single module.
 */
define(
    [
        "angular",
        "./thesaurus-routes",
        "./thesaurus-services",
        "./thesaurus-controllers"
    ],
    function (angular, routes, services, controllers) {
        "use strict";

        var mod = angular.module(
            "narthex.thesaurus",
            [
                "ngCookies",
                "ngRoute",
                "thesaurus.routes",
                "thesaurus.services"
            ]
        );
        return mod;
    }
);
