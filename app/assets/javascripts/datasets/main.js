define(
    [
        "angular",
        "./datasets-controllers",
        "./datasets-routes",
        "./datasets-services"
    ],
    function (angular, controllers) {
        "use strict";

        var mod = angular.module(
            "narthex.datasets",
            [
                "ngRoute",
                "datasets.routes",
                "datasets.services",
                "narthex.common"
            ]
        );

        mod.controller('DatasetEntryCtrl', controllers.DatasetEntryCtrl);
        mod.config(function ($rootScopeProvider) {
            $rootScopeProvider.digestTtl(15);
        });
        return mod;
    });


