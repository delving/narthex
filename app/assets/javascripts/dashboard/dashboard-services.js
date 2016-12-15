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

    function buildUrls(user) {
        user.narthexAPI = user.narthexDomain + '/narthex/api/' + user.apiKey;
//        console.log("user", user);
    }

    var mod = angular.module("dashboard.services", ["narthex.common"]);
    mod.factory(
        "userService",
        ["$http", "$q", "playRoutes",
            function ($http, $q, playRoutes) {
                var user;
                var main = playRoutes.web.MainController;
                return {
                    logout: function () {
                        console.log("Attempt logout");
                        return main.logout().get().then(function (response) {
                            user = undefined;
                            return "logged out"
                        });
                    },
                    checkLogin: function () {
                        console.log("Check login");
                        return main.jsRoutes().get().then(
                            function (response) {
                                user = {
                                    username: "admin",
                                    apiKey: "foo"
                                };
                                buildUrls(user);
                                return user;
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
        user: function ($q, userService) {
            var deferred = $q.defer();
            var user = userService.getUser();
            if (user) {
                deferred.resolve(user);
            }
            else {
                userService.checkLogin().then(
                    function (revealedUser) {
                        if (revealedUser) {
                            deferred.resolve(revealedUser)
                        }
                        else {
                            deferred.reject();
                        }
                    },
                    function (reason) {
                        deferred.reject();
                    }
                );
            }
            return deferred.promise;
        }
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
