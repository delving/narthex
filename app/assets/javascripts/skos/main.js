/**
 * Main, shows the start page and provides controllers for the header and the footer.
 * This the entry module which serves as an entry point so other modules only have to include a
 * single module.
 */
define(
    [
        "angular",
        "./skos-services",
        "./skos-controllers",
        "../login/login-services"
    ],
    function (angular, services, controllers) {
        "use strict";

        var skosRoutes = angular.module("skos.routes", ["narthex.common", "login.services"]);
        skosRoutes.config([
            "$routeProvider", "userResolve",
            function ($routeProvider, userResolve) {
                $routeProvider.when(
                    "/skos",
                    {
                        templateUrl: "/narthex/assets/templates/skos-main.html",
                        controller: controllers.SkosChooseCtrl,
                        resolve: userResolve,
                        reloadOnSearch: false
                    }
                ).when(
                    "/skos/:conceptSchemeA/:conceptSchemeB",
                    {
                        templateUrl: "/narthex/assets/templates/skos-map.html",
                        controller: controllers.SkosMapCtrl,
                        resolve: userResolve,
                        reloadOnSearch: false
                    }
                )
            }
        ]);

        return angular.module("narthex.skos", [
            "ngCookies",
            "ngRoute",
            "skos.routes",
            "skos.services"
        ]);
    }
);
