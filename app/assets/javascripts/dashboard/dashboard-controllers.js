/**
 * Dashboard controllers.
 */
define(["angular"], function () {
    "use strict";

    /**
     * user is not a service, but stems from userResolve (Check ../user/dashboard-services.js) object used by dashboard.routes.
     */
    var DashboardCtrl = function ($scope, user, dashboardService, fileUpload, $location, $upload, $timeout) {

        $scope.user = user;
        $scope.image = 'png';

        $scope.onFileSelect = function ($files) {
            //$files: an array of files selected, each file has name, size, and type.
            for (var i = 0; i < $files.length; i++) {
                var file = $files[i];
                $scope.image = "gif";
                $scope.upload = $upload.upload(
                    {
                        url: '/dashboard/upload', //upload.php script, node.js route, or servlet url
                        // method: POST or PUT,
                        // headers: {'header-key': 'header-value'},
                        // withCredentials: true,
                        data: {myObj: $scope.myModelObj},
                        file: file
                    }
                ).progress(
                    function (evt) {
                        console.log('percent: ' + parseInt(100.0 * evt.loaded / evt.total));
                    }
                ).success(
                    function (data, status, headers, config) {
                        // file is uploaded successfully
                        $scope.image = "png";
                        fetchFileList();
                    }
                );
                //.error(...)
                //.then(success, error, progress);
            }
        };

        function checkFileStatus(file) {
            dashboardService.status(file.name).then(function (data) {
                if (data.problem) {
                    file.status = data.problem;
                }
                else {
                    file.status = data;
                    if (file && !file.status.complete) {
                        $scope.image = "gif";
                        $timeout(function () {
                            checkFileStatus(file)
                        }, 1000)
                    }
                    else {
                        $scope.image = "png";
                    }
                }
            })
        }

        function fetchFileList() {
            dashboardService.list().then(function (data) {
                $scope.files = data;
                _.forEach($scope.files, function (file) {
                    checkFileStatus(file)
                });
            });
        }

        fetchFileList();

        $scope.viewFile = function (file) {
            $location.path("/dashboard/" + file.name);
        };

    };

    DashboardCtrl.$inject = [
        "$scope", "user", "dashboardService", "fileUpload", "$location", "$upload", "$timeout"
    ];

    var FileDetailCtrl = function ($scope, $routeParams, $timeout, dashboardService, userService) {

        $scope.fileName = $routeParams.fileName;

        dashboardService.index($scope.fileName).then(function (data) {
            $scope.tree = data;
        });

        $scope.selectNode = function (node) {
            if (!node.lengths.length) return;
            $scope.node = node;
            $scope.sampleSize = 100;
            $scope.histogramSize = 100;
//            $scope.fetchLengths();
            var user = userService.getUser();
            $scope.uniquePath = "/api/" + user.email + "/" + $scope.fileName + "/unique-text" + $scope.node.path;
            $scope.histogramPath = "/api/" + user.email + "/" + $scope.fileName + "/histogram-text" + $scope.node.path;
            dashboardService.nodeStatus($scope.fileName, node.path).then(function (data) {
                $scope.status = data;
                $scope.fetchSample();
            });
        };

//        $scope.fetchLengths = function() {
//            $scope.activeView = "lengths";
//            $scope.sample = undefined;
//            $scope.histogram = undefined;
//        };

        $scope.fetchSample = function () {
            $scope.activeView = "sample";
            dashboardService.sample($scope.fileName, $scope.node.path, $scope.sampleSize).then(function (data) {
                $scope.sample = data;
                $scope.histogram = undefined;
            });
        };

        $scope.fetchHistogram = function () {
            $scope.activeView = "histogram";
            dashboardService.histogram($scope.fileName, $scope.node.path, $scope.histogramSize).then(function (data) {
                $scope.histogram = data;
                $scope.sample = undefined;
            });
        };

        $scope.isMoreSample = function () {
            if (!($scope.status && $scope.status.samples)) return false;
            var which = _.indexOf($scope.status.samples, $scope.sampleSize, true);
            return which < $scope.status.samples.length - 1;
        };

        $scope.moreSample = function () {
            var which = _.indexOf($scope.status.samples, $scope.sampleSize, true);
            $scope.sampleSize = $scope.status.samples[which + 1];
            $scope.fetchSample();
        };

        $scope.isMoreHistogram = function () {
            if (!($scope.status && $scope.status.histograms)) return false;
            var which = _.indexOf($scope.status.histograms, $scope.histogramSize, true);
            return which < $scope.status.histograms.length - 1;
        };

        $scope.moreHistogram = function () {
            var which = _.indexOf($scope.status.histograms, $scope.histogramSize, true);
            $scope.histogramSize = $scope.status.histograms[which + 1];
            $scope.fetchHistogram();
        };

        $scope.$watch("filterTyped", function (filterTyped, oldFilterTyped) {
            if ($scope.filterTicking) {
                $timeout.cancel($scope.filterTicking);
                $scope.filterTicking = undefined;
            }
            $scope.filterTicking = $timeout(
                function () {
                    $scope.filterText = filterTyped;
                },
                500
            );
        });

        /**
         * Scrolls up and down to a named anchor hash, or top/bottom of an element
         * @param {Object} options: hash - named anchor, element - html element (usually a div) with id
         * eg. scrollTo({'hash': 'page-top'})
         * eg. scrollto({'element': '#document-list-container'})
         */
        $scope.scrollTo = function (options) {
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
        };
    };

    FileDetailCtrl.$inject = ["$scope", "$routeParams", "$timeout", "dashboardService", "userService"];

    var TreeCtrl = function ($scope) {
        $scope.$watch('tree', function (tree, oldTree) {
            if (tree) {
                $scope.node = tree;
            }
        });
    };

    TreeCtrl.$inject = ["$scope"];

    var TreeNodeCtrl = function ($scope) {
//        $scope.setNode = function(node) {
//            console.log("node", node);
//            $scope.node = node;
//        };
    };

    TreeNodeCtrl.$inject = ["$scope"];

    return {
        DashboardCtrl: DashboardCtrl,
        TreeCtrl: TreeCtrl,
        TreeNodeCtrl: TreeNodeCtrl,
        FileDetailCtrl: FileDetailCtrl
    };

});
