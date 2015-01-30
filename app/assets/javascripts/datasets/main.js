define(
    [
        "angular",
        "./datasets-controllers",
        "./datasets-services"
    ],
    function (angular, controllers) {
        "use strict";

        var datasetsRoutes = angular.module("datasets.routes", ["narthex.common", "dashboard.services"]);
        datasetsRoutes.config(
            [
                "$routeProvider", "userResolve",
                function ($routeProvider, userResolve) {
                    $routeProvider.when(
                        "/datasets", {
                            templateUrl: "/narthex/assets/templates/datasets.html",
                            controller: controllers.DatasetsCtrl,
                            resolve: userResolve,
                            reloadOnSearch: false
                        }
                    );
                }
            ]
        );

        var narthexDatasets = angular.module("narthex.datasets", [
            "ngRoute",
            "datasets.routes",
            "datasets.services",
            "narthex.common"
        ]);

        narthexDatasets.controller('DatasetEntryCtrl', controllers.DatasetEntryCtrl);
        narthexDatasets.config(function ($rootScopeProvider) {
            $rootScopeProvider.digestTtl(15);
        });
        return narthexDatasets;
    });


