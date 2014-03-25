/*
 * http://uncorkedstudios.com/blog/multipartformdata-file-upload-with-angularjs
 */

define(["angular"], function (angular) {
    "use strict";

    var mod = angular.module("common.fileUpload", []);

    mod.service(
        "fileUpload",
        [
            "$http",
            function ($http) {
                return {
                    uploadFileToUrl: function (file, uploadUrl) {
                        var fd = new FormData();
                        fd.append('file', file);
                        $http.post(
                            uploadUrl,
                            fd,
                            {
                                transformRequest: angular.identity,
                                headers: {'Content-Type': undefined}
                            }
                        ).success(
                            function () {
                                console.log("file-upload: success!");
                            }
                        ).error(
                            function () {
                                console.log("file-upload: failure!");
                            }
                        );
                    }
                };
            }
        ]
    );
    return mod;
});
