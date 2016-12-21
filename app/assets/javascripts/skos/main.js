/**
 * Main, shows the start page and provides controllers for the header and the footer.
 * This the entry module which serves as an entry point so other modules only have to include a
 * single module.
 */
define(
    [
        "angular",
        "./skos-services",
        "./skos-controllers"
    ],
    function (angular, services, controllers) {
        "use strict";

        var skosRoutes = angular.module("skos.routes", ["narthex.common"]);
        skosRoutes.config([
            "$routeProvider",
            function ($routeProvider) {
                $routeProvider.when(
                    "/skos",
                    {
                        templateUrl: "/narthex/assets/templates/skos-list.html",
                        controller: controllers.SkosListCtrl,
                        reloadOnSearch: false
                    }
                ).when(
                    "/skos/:specA/:specB",
                    {
                        templateUrl: "/narthex/assets/templates/skos-map.html",
                        controller: controllers.SkosMapCtrl,
                        reloadOnSearch: false
                    }
                )
            }
        ]);

        var narthexSkos = angular.module("narthex.skos", [
            "ngCookies",
            "ngRoute",
            "skos.routes",
            "skos.services"
        ]);

        narthexSkos.controller('SkosListEntryCtrl', controllers.SkosListEntryCtrl);

        return  narthexSkos;
    }
);
