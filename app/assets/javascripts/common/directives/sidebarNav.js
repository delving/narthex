define(["angular"], function (angular) {
    "use strict";

    var mod = angular.module("common.directives.sidebarNav", []);

    mod.directive("sidebarNav", ["$location", function($location) {
        return function(scope, element, attrs) {
            var links = element.find('a'),
                onClass = attrs.sidebarNav || 'on',
                routePattern,
                link,
                url,
                currentLink,
                urlMap = {},
                i;

            if (!$location.$$html5) {
//                routePattern = /^#[^/]*/;
                routePattern = "/#/";
            }

            for (i = 0; i < links.length; i++) {
                link = angular.element(links[i]);
                url = link.attr('href');
                if ($location.$$html5) {
                    urlMap[url] = link;
                } else {
                    urlMap[url.replace(routePattern, '/')] = link;
                }
            }

            scope.$on('$routeChangeStart', function() {
                var pathLink = urlMap[$location.path()];
                if (pathLink) {
                    if (currentLink) {
                        currentLink.removeClass(onClass);
                    }
                    currentLink = pathLink;
                    currentLink.addClass(onClass);
                }
            });
        };
    }]);

    return mod;
});