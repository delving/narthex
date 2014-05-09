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

define(["angular", "common"], function (angular) {
    "use strict";

    var mod = angular.module("home.services", ["narthex.common"]);
    mod.factory(
        "userService",
        ["$http", "$q", "playRoutes",
            function ($http, $q, playRoutes) {
                var user;
                var app = playRoutes.controllers.Application;
                return {
                    loginUser: function (credentials) {
                        return app.login().post(credentials).then(function (response) {
                            user = response.data.user;
                            return user;
                        });
                    },
                    logout: function () {
                        console.log("notifying the server of logout");
                        return app.logout().get().then(function (response) {
                            user = undefined;
                            return "logged out"
                        });
                    },
                    checkLogin: function () {
                        return app.checkLogin().get().then(function (response) {
                            user = response.data.user;
                            return user;
                        }, function(problem) {
                            // todo: react properly
                        });
                    },
                    getUser: function () {
                        return user;
                    }
                };
            }
        ]
    );

    /**
     * Add this object to a route definition to only allow resolving the route if the user is
     * logged in. This also adds the contents of the objects as a dependency of the controller.
     */
    mod.constant("userResolve", {
        user: [
            "$q", "userService",
            function ($q, userService) {
                var deferred = $q.defer();
                var user = userService.getUser();
                if (user) {
                    deferred.resolve(user);
                }
                else {
                    userService.checkLogin().then(
                        function (revealedUser) {
                            deferred.resolve(user)
                        },
                        function (reason) {
                            deferred.reject();
                        }
                    );
                }
                return deferred.promise;
            }
        ]
    });

    /**
     * If the current route does not resolve, go back to the start page.
     */
    var handleRouteError = function ($rootScope, $location) {
        $rootScope.$on("$routeChangeError", function (e, next, current) {
            $location.path("/");
        });
    };
    handleRouteError.$inject = ["$rootScope", "$location"];
    mod.run(handleRouteError);
    return mod;
});
