/**
 * Main, shows the start page and provides controllers for the header and the footer.
 * This the entry module which serves as an entry point so other modules only have to include a
 * single module.
 */
define(
    [
        "angular",
        "./login-services",
        "./login-controllers"
    ],
    function (angular, services, controllers) {
        "use strict";

        var loginRoutes = angular.module("login.routes", ["narthex.common"]);
        loginRoutes.config(["$routeProvider", function ($routeProvider) {
            $routeProvider
                .when(
                "/",
                {
                    templateUrl: "/narthex/assets/templates/login.html",
                    controller: controllers.LoginCtrl
                }
            ).otherwise(
                {
                    templateUrl: "/narthex/assets/templates/notFound.html"
                }
            );
        }]);

        var narthexLogin = angular.module(
            "narthex.login",
            [
                "ngCookies",
                "ngRoute",
                "ngStorage",
                "login.routes",
                "login.services"
            ]
        );
        narthexLogin.controller("HeaderCtrl", controllers.HeaderCtrl);
        narthexLogin.controller("SidebarCtrl", controllers.SidebarCtrl);
        return narthexLogin;
    }
);
