/**
 * Main, shows the start page and provides controllers for the header and the footer.
 * This the entry module which serves as an entry point so other modules only have to include a
 * single module.
 */
define(
    [
        "angular",
        "./thesaurus-services",
        "./thesaurus-controllers",
        "../login/login-services"
    ],
    function (angular, services, controllers) {
        "use strict";

        var thesaurusRoutes = angular.module("thesaurus.routes", ["narthex.common", "login.services"]);
        thesaurusRoutes.config([
            "$routeProvider", "userResolve",
            function ($routeProvider, userResolve) {
                $routeProvider.when(
                    "/thesaurus",
                    {
                        templateUrl: "/narthex/assets/templates/thesaurus-choose.html",
                        controller: controllers.ThesaurusChooseCtrl,
                        resolve: userResolve,
                        reloadOnSearch: false
                    }
                ).when(
                    "/thesaurus/:conceptSchemeA/:conceptSchemeB",
                    {
                        templateUrl: "/narthex/assets/templates/thesaurus-map.html",
                        controller: controllers.ThesaurusMapCtrl,
                        resolve: userResolve,
                        reloadOnSearch: false
                    }
                )
            }
        ]);

        var narthexThesaurus = angular.module("narthex.thesaurus", [
            "ngCookies",
            "ngRoute",
            "thesaurus.routes",
            "thesaurus.services"
        ]);
        return narthexThesaurus;
    }
);
