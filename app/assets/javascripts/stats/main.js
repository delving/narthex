/**
 * Stats module - shows processing activity statistics for the last 24 hours.
 */
define(
    [
        "angular",
        "./stats-controllers"
    ],
    function (angular, controllers) {
        "use strict";

        var statsRoutes = angular.module("stats.routes", ["narthex.common"]);
        statsRoutes.config([
            "$routeProvider",
            function ($routeProvider) {
                $routeProvider.when(
                    "/stats",
                    {
                        templateUrl: "/narthex/assets/templates/stats.html",
                        controller: controllers.StatsCtrl
                    }
                );
            }
        ]);

        return angular.module("narthex.stats", [
            "ngRoute",
            "stats.routes",
            "narthex.common"
        ]);
    }
);
