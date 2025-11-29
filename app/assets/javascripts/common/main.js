/**
 * Common functionality.
 */
define(
    [
        "angular",
        "./services/pageScroll",
        "./services/playRoutes",
        "./services/modalAlert",
        "./filters",
        "./directives/scrollable",
        "./directives/fileModel",
        "./venn" // todo: further integrate
    ],
    function (angular) {
        "use strict";

        return angular.module("narthex.common", [
            "common.pageScroll",
            "common.playRoutes",
            "common.modalAlert",
            "common.filters",
            "common.directives.scrollable",
            "common.directives.fileModel"
        ]);
    }
);
