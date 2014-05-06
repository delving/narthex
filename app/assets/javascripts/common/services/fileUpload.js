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
