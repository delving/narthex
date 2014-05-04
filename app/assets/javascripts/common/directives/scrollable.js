/**
 * A common directive.
 * It would also be ok to put all directives into one file, or to define one RequireJS module
 * that references them all.
 */
define(["angular"], function (angular) {
    "use strict";

    var mod = angular.module("common.directives.scrollable", []);

    mod.directive("scrollable", ["$window", "$timeout", function($window, $timeout) {
        return {
            restrict: 'E,A',
            replace: false,
            scope: true,
            link: function($scope, $element, $attrs){
                $timeout(function(){
                    var offset = $attrs.offset,
                        height = $attrs.fixedHeight;
                    $scope.elHeight = null;
                    function initialize () {
                        if(!height){
                            $scope.elHeight = $window.innerHeight - offset;
                        }
                        else {
                            $scope.elHeight = height;
                        }
                    }
                    initialize();
                    return angular.element($window).bind('resize', function() {
                        initialize();
                        return $scope.$apply();
                    });
                },500,true);
            }
        }
    }]);

    return mod;
});

