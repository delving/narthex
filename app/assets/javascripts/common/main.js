/**
 * Common functionality.
 */
define(
    [
        "angular",
        "./services/helper",
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
                "common.helper",
                "common.fileUpload",
                "common.playRoutes",
                "common.filters",
                "common.directives.example",
                "common.directives.fileModel"
            ]
        );
    }
);
