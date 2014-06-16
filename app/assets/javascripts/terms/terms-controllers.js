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

    var TermsCtrl = function ($scope, $routeParams, dashboardService) {
        $scope.fileName = $routeParams.fileName;
        $scope.path = $routeParams.path;
        $scope.histogramSize = parseInt($routeParams.size || "100");
        $scope.selectedValue = "";
        console.log("path", $scope.path);

        dashboardService.histogram($scope.fileName, $scope.path, $scope.histogramSize).then(function (data) {
            $scope.histogram = data;
        });

        $scope.selectValue = function(count) {
            $scope.selectedValue = count[1];
            alert("Selected "+$scope.selectedValue);
        };

    };

    TermsCtrl.$inject = ["$scope", "$routeParams", "dashboardService"];

    return {
        TermsCtrl: TermsCtrl
    };
});
