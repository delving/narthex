define(
    [
        "angular",
        "./dataset-list-controllers",
        "./dataset-list-services",
        "./activity-modal-controller"
    ],
    function (angular, controllers, services, activityModalController) {
        "use strict";

        var datasetListRoutes = angular.module("datasetList.routes", ["narthex.common"]);
        datasetListRoutes.config(
            [
                "$routeProvider",
                function ($routeProvider) {
                    $routeProvider.when(
                        "/", {
                            templateUrl: "/narthex/assets/templates/dataset-list.html?v=0.8.2.15a",
                            controller: controllers.DatasetListCtrl,
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
        narthexDatasetList.controller("IndexCtrl", controllers.IndexCtrl);
        narthexDatasetList.controller('ActivityModalCtrl', activityModalController);
        var config = function config($rootScopeProvider) {
            $rootScopeProvider.digestTtl(15);
        };

        config.$inject = ['$rootScopeProvider'];
        narthexDatasetList.config(config);
        return narthexDatasetList;
    });


