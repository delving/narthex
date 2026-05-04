//===========================================================================
//    Copyright 2026 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//===========================================================================

define(
    [
        "angular",
        "./recdefs-controllers",
        "./recdefs-services"
    ],
    function (angular, controllers) {
        "use strict";

        var recdefsRoutes = angular.module("recdefs.routes", ["narthex.common"]);
        recdefsRoutes.config(
            [
                "$routeProvider",
                function ($routeProvider) {
                    $routeProvider.when(
                        "/recdefs", {
                            templateUrl: "/narthex/assets/templates/recdefs-list.html",
                            controller: controllers.RecDefsListCtrl,
                            reloadOnSearch: false
                        }
                    );
                }
            ]
        );

        var narthexRecDefs = angular.module("narthex.recdefs", [
            "ngRoute",
            "recdefs.routes",
            "recdefs.services",
            "narthex.common"
        ]);

        narthexRecDefs.controller('RecDefsListCtrl', controllers.RecDefsListCtrl);

        return narthexRecDefs;
    }
);
