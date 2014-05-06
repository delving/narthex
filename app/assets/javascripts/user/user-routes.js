/**
 * Configure routes of user module.
 */
define(
    ["angular", "./user-controllers", "common"],
    function (angular, controllers) {
        var mod = angular.module("user.routes", ["user.services", "xml-ray.common"]);
        mod.config([
            "$routeProvider",
            function ($routeProvider) {
                $routeProvider.when(
                    "/login",
                    {
                        templateUrl: "/assets/templates/user/login.html",
                        controller: controllers.LoginCtrl
                    }
                );
            }
        ]);
        return mod;
    }
);
