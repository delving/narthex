/**
 * Common functionality.
 */
define(
    [
        "angular",
        "./services/pageScroll",
        "./services/playRoutes",
        "./filters",
        "./directives/scrollable",
        "./directives/fileModel"
    ],
    function (angular) {
        "use strict";

        return angular.module(
            "narthex.common",
            [
                "common.pageScroll",
                "common.playRoutes",
                "common.filters",
                "common.directives.scrollable",
                "common.directives.fileModel"
            ]
        );
    }
);
