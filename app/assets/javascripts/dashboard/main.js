/**
 * Dashboard, shown after user is logged in.
 * dashboard/main.js is the entry module which serves as an entry point so other modules only have
 * to include a single module.
 */
define(
    [
        "angular",
        "./dashboard-controllers",
        "./dashboard-routes",
        "./dashboard-services",
        "../common/directives/scrollable"
    ],
    function (angular, controllers) {
        "use strict";

        var mod = angular.module(
            "xml-ray.dashboard",
            [
                "ngRoute",
                "dashboard.routes",
                "dashboard.services",
                "common.directives.scrollable"
            ]
        );

        mod.controller('TreeCtrl', controllers.TreeCtrl);
        mod.controller('TreeNodeCtrl', controllers.TreeNodeCtrl);
        mod.config(function ($rootScopeProvider) {
            $rootScopeProvider.digestTtl(15);
        });
        return mod;
    });
