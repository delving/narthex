define(
    [
        "angular",
        "./dataset-controllers",
        "./dataset-routes",
        "./dataset-services"
    ],
    function (angular, controllers) {
        "use strict";

        var mod = angular.module(
            "narthex.dataset",
            [
                "ngRoute",
                "dataset.routes",
                "dataset.services",
                "narthex.common"
            ]
        );

        mod.controller('TreeCtrl', controllers.TreeCtrl);
        mod.controller('NodeCtrl', controllers.NodeCtrl);
        mod.config(function ($rootScopeProvider) {
            $rootScopeProvider.digestTtl(15);
        });
        return mod;
    });


