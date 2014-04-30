/**
 * User controllers.
 */
define(["angular"], function (angular) {
    "use strict";

    var LoginCtrl = function ($scope, $location, userService) {
        $scope.credentials = {
            "email" : "gerald@delving.eu",
            "password" : "gumby"
        };

        $scope.login = function (credentials) {
            userService.loginUser(credentials).then(function (/*user*/) {
                $location.path("/dashboard");
            });
        };
    };
    LoginCtrl.$inject = ["$scope", "$location", "userService"];

    return {
        LoginCtrl: LoginCtrl
    };

});
