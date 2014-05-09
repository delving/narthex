/**
 * User package module.
 * Manages all sub-modules so other RequireJS modules only have to import the package.
 */
define(
    [
        "angular",
        "./user-routes",
        "./user-services"
    ],
    function (angular) {
        "use strict";

        return angular.module(
            "narthex.user",
            [
                "ngCookies",
                "ngRoute",
                "user.routes",
                "user.services"
            ]
        );
    }
);
