define(
    [
        "angular",
        "./dataset-list-controllers",
        "./dataset-list-services"
    ],
    function (angular, controllers) {
        "use strict";

        var datasetListRoutes = angular.module("datasetList.routes", ["narthex.common", "dashboard.services"]);
        datasetListRoutes.config(
            [
                "$routeProvider", "userResolve",
                function ($routeProvider, userResolve) {
                    $routeProvider.when(
                        "/dataset-list", {
                            templateUrl: "/narthex/assets/templates/dataset-list.html",
                            controller: controllers.DatasetListCtrl,
                            resolve: userResolve,
                            reloadOnSearch: false
                        }
                    );
                }
            ]
        );

        var narthexDatasetList = angular.module("narthex.datasetList", [
            "ngRoute",
            "datasetList.routes",
            "datasetList.services",
            "narthex.common"
        ]);

        narthexDatasetList.controller('DatasetEntryCtrl', controllers.DatasetEntryCtrl);
        narthexDatasetList.config(function ($rootScopeProvider) {
            $rootScopeProvider.digestTtl(15);
        });
        return narthexDatasetList;
    });


