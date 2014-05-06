/**
 * User controllers.
 */
define(["angular"], function (angular) {

    "use strict";

    var EMAIL_COOKIE = "XML-RAY-EMail";

    var LoginCtrl = function ($scope, $location, userService, $cookies) {
        $scope.credentials = {
            email: $cookies[EMAIL_COOKIE]
        };
        $scope.login = function (credentials) {
            userService.loginUser(credentials).then(
                function (/*user*/) {
                    $cookies[EMAIL_COOKIE] = credentials.email;
                    $location.path("/dashboard");
                },
                function (rejection) {
                    if (rejection.status == 401) {
                        $scope.errorMessage = rejection.data.problem;
                        $scope.credentials.password = "";
                    }
                }
            );
        };
    };
    LoginCtrl.$inject = ["$scope", "$location", "userService", "$cookies"];

    return {
        LoginCtrl: LoginCtrl
    };

});
