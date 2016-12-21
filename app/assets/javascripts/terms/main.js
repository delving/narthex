/**
 * Main, shows the start page and provides controllers for the header and the footer.
 * This the entry module which serves as an entry point so other modules only have to include a
 * single module.
 */
define(
    [
        "angular",
        "./terms-services",
        "./terms-controllers"
    ],
    function (angular, services, controllers) {
        "use strict";

        var termsRoutes = angular.module("terms.routes", ["narthex.common"]);
        termsRoutes.config([
            "$routeProvider",
            function ($routeProvider) {
                $routeProvider.when(
                    "/terms/:spec",
                    {
                        templateUrl: "/narthex/assets/templates/terms.html",
                        controller: controllers.TermsCtrl,
                        reloadOnSearch: false
                    }
                )
            }
        ]);

        var narthexTerms = angular.module("narthex.terms", [
            "ngCookies",
            "ngRoute",
            "terms.routes",
            "terms.services"
        ]);
        return narthexTerms;
    }
);
