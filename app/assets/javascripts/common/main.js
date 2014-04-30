/**
 * Common functionality.
 */
define(
    [
        "angular",
        "./services/fileUpload",
        "./services/playRoutes",
        "./filters",
        "./directives/example",
        "./directives/fileModel"
    ],
    function (angular) {
        "use strict";

        return angular.module(
            "xml-ray.common",
            [
                "common.fileUpload",
                "common.playRoutes",
                "common.filters",
                "common.directives.example",
                "common.directives.fileModel"
            ]
        );
    }
);
