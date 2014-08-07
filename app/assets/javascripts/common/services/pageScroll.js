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

define(["angular"], function (angular) {
    "use strict";

    var mod = angular.module("common.pageScroll", []);

    mod.service(
        "pageScroll",
        [
            "$timeout", "$location",
            function ($timeout, $location) {
                return {
                    scrollTo: function (options) {
                        /**
                         * Scrolls up and down to a named anchor hash, or top/bottom of an element
                         * {Object} options: hash - named anchor, element - html element (usually a div) with id
                         * eg. scrollTo({'hash': 'page-top'})
                         * eg. scrollto({'element': '#document-list-container'})
                         */
                        options = options || {};
                        var hash = options.hash || undefined,
                            element = options.element || undefined,
                            direction = options.direction || 'up';
                        // navigate to hash
                        if (hash) {
                            var old = $location.hash();
                            $location.hash(hash);
                            $anchorScroll();
                            $location.hash(old);//reset to old location in order to maintain routing logic (no hash in the url)
                        }
                        // scroll the provided dom element if it exists
                        if (element && $(options.element).length) {
                            var scrollElement = $(options.element);
                            // get the height from the actual content, not the container
                            var scrollHeight = scrollElement[0].scrollHeight;
                            var distance = '';
                            if (!direction || direction == 'up') {
                                distance = -scrollHeight;
                            }
                            else {
                                distance = scrollHeight;
                            }
                            $timeout(function () {
                                scrollElement.stop().animate({
                                    scrollLeft: '+=' + 0,
                                    scrollTop: '+=' + distance
                                });
                            }, 250);
                        }
                    }
                };
            }
        ]
    );
    return mod;
});
