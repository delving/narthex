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
        user.oaiPmhRaw = user.narthexDomain + '/narthex/oai-pmh/' + user.oaiPmhKey;
        user.oaiPmhEnriched = user.narthexDomain + '/narthex/oai-pmh/enriched/' + user.oaiPmhKey;
//        console.log("user", user);
    }

    var mod = angular.module("home.services", ["narthex.common"]);
    mod.factory(
        "userService",
        ["$http", "$q", "playRoutes",
            function ($http, $q, playRoutes) {
                var user;
                var app = playRoutes.web.Application;
                return {
                    loginUser: function (credentials) {
                        return app.login().post(credentials).then(
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
                        console.log("notifying the server of logout");
                        return app.logout().get().then(function (response) {
                            user = undefined;
                            return "logged out"
                        });
                    },
                    checkLogin: function () {
                        return app.checkLogin().get().then(
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
