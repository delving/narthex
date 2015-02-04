/**
 * Main, shows the start page and provides controllers for the header and the footer.
 * This the entry module which serves as an entry point so other modules only have to include a
 * single module.
 */
define(
    [
        "angular",
        "./categories-services",
        "./categories-controllers"
    ],
    function (angular, services, controllers) {
        "use strict";

        var categoriesRoutes = angular.module("categories.routes", ["narthex.common", "dashboard.services"]);
        categoriesRoutes.config([
            "$routeProvider", "userResolve",
            function ($routeProvider, userResolve) {
                $routeProvider.when(
                    "/categories/:spec",
                    {
                        templateUrl: "/narthex/assets/templates/category-set.html",
                        controller: controllers.CategorySetCtrl,
                        resolve: userResolve,
                        reloadOnSearch: false
                    }
                ).when(
                    "/categories",
                    {
                        templateUrl: "/narthex/assets/templates/category-monitor.html",
                        controller: controllers.CategoryMonitorCtrl,
                        resolve: userResolve
                    }
                )
            }
        ]);

        return angular.module("narthex.categories", [
            "ngCookies",
            "ngRoute",
            "categories.routes",
            "categories.services"
        ]);
    }
);
