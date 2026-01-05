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
                            templateUrl: "/narthex/assets/templates/dataset-list.html?v=0.8.3.6",
                            controller: controllers.DatasetListCtrl,
                            reloadOnSearch: false
                        }
                    ).when(
                        "/sip-creator", {
                            templateUrl: "/narthex/assets/templates/sip-creator-downloads.html?v=0.8.3.6",
                            controller: 'SipCreatorDownloadsCtrl'
                        }
                    );
                }
            ]
        );

        var narthexDatasetList = angular.module("narthex.datasetList", [
            "ngRoute",
            "datasetList.routes",
            "datasetList.services",
            "narthex.common",
            "narthex.defaultMappings"
        ]);

        narthexDatasetList.controller('DatasetEntryCtrl', controllers.DatasetEntryCtrl);
        narthexDatasetList.controller("IndexCtrl", controllers.IndexCtrl);
        narthexDatasetList.controller('ActivityModalCtrl', activityModalController);
        narthexDatasetList.controller('SipCreatorDownloadsCtrl', controllers.SipCreatorDownloadsCtrl);
        var config = function config($rootScopeProvider) {
            $rootScopeProvider.digestTtl(15);
        };

        config.$inject = ['$rootScopeProvider'];
        narthexDatasetList.config(config);
        return narthexDatasetList;
    });


