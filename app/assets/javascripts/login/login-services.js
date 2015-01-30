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
        user.enrichmentPrefix = user.naveDomain + '/resource/thesaurusenrichment';
//        console.log("user", user);
    }

    var mod = angular.module("login.services", ["narthex.common"]);
    mod.factory(
        "userService",
        ["$http", "$q", "playRoutes",
            function ($http, $q, playRoutes) {
                var user;
                var main = playRoutes.web.MainController;
                return {
                    loginUser: function (credentials) {
                        console.log("Attempt login");
                        return main.login().post(credentials).then(
                            function (response) {
                                user = response.data;
                                buildUrls(user);
                                return { profile: user };
                            },
                            function (response) {
                                user = null;
                                return { problem: response.data };
                            }
                        );
                    },
                    logout: function () {
                        console.log("Attempt logout");
                        return main.logout().get().then(function (response) {
                            user = undefined;
                            return "logged out"
                        });
                    },
                    checkLogin: function () {
                        console.log("Check login");
                        return main.checkLogin().get().then(
                            function (response) {
                                user = response.data;
                                buildUrls(user);
                                return user;
                            },
                            function (problem) {
                                console.log('check login failed', problem);
                                user = null;
                                return null;
                            });
                    },
                    setProfile: function (profile) {
                        return main.setProfile().post(profile).then(
                            function (response) {
                                user = response.data;
                                buildUrls(user);
                                return user;
                            }
                        );
                    },
                    setPassword: function (newPassword) {
                        var payload = { "newPassword": newPassword };
                        return main.setPassword().post(payload);
//                        return main.setPassword().post(payload).then(
//                            function (response) {
//                                return response;
//                            }
//                        );
                    },
                    listActors: function () {
                        return main.listActors().get().then(
                            function (response) {
                                return response.data.actorList;
                            }
                        );
                    },
                    createActor: function (credentials) {
                        return main.createActor().post(credentials).then(
                            function (response) {
                                return response.data.actorList;
                            }
                        );
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

//    todo: the user function above is not injected properly, maybe do something like this:
//     DashboardCtrl.$inject = [
//        "$scope", "userResolve", "dashboardService", "fileUpload", "$location", "$upload", "$timeout"
//    ];

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
