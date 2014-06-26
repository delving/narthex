//===========================================================================
//    Copyright 2014 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================

define(["angular"], function (angular) {
    "use strict";

    var TermsCtrl = function ($scope, $location, $routeParams, dashboardService) {
        $scope.fileName = $routeParams.fileName;
        $scope.path = $routeParams.path;
        $scope.histogramSize = parseInt($routeParams.size || "100");
        $scope.selectedValue = null;
        $scope.skosList = null;
        $scope.skosName = null;
        $scope.skosSought = null;

        dashboardService.histogram($scope.fileName, $scope.path, $scope.histogramSize).then(function (data) {
            $scope.histogram = data;
        });

        dashboardService.listSkos().then(function(data) {
            $scope.skosList = data.list;
        });

        $scope.selectValue = function (count) {
            $scope.selectedValue = count[1];
            $scope.skosSought = $scope.selectedValue;
        };

        $scope.goToDataset = function () {
            $location.path("/dataset/" + $scope.fileName);
            $location.search({
                path: $scope.path,
                view: "histogram"
            });
        };

        $scope.setSkosList = function(name) {
            $scope.skosName = name;
        };

        $scope.setSkosSought = function(value) {
            $scope.skosSought = value;
        };

        $scope.$watch("skosSought", function(skosSought, old) {
            if (!skosSought || !$scope.skosName) return;
            dashboardService.searchSkos($scope.skosName, skosSought).then(function(data) {
                $scope.skosFound = data.search;
            });
        });
    };

    TermsCtrl.$inject = ["$scope", "$location", "$routeParams", "dashboardService"];

    return {
        TermsCtrl: TermsCtrl
    };
});
