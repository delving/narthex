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

    var SkosListCtrl = function ($rootScope, $scope, $location, $routeParams, skosService, user) {
        if (user == null) $location.path("/");

        $scope.newDataset = {};

        function checkNewEnabled() {
            if ($scope.newDataset.specTyped)
                $scope.newDataset.spec = $scope.newDataset.specTyped.trim().replace(/\W+/g, "_").replace(/_+/g, "_").toLowerCase();
            else
                $scope.newDataset.spec = "";
            $scope.newDataset.enabled = $scope.newDataset.spec.length;
        }

        $scope.$watch("newDataset.specTyped", checkNewEnabled);

        $scope.cancelNewFile = function () {
            $scope.newFileOpen = false;
        };

        $scope.decorateSkos = function (skos) {
            skos.edit = angular.copy(skos);
            if (skos.skosUploadTime) {
                var dt = skos.skosUploadTime.split('T');
                skos.skosUploadTime = {
                    d: dt[0],
                    t: dt[1].split('+')[0]
                };
            }
            return skos;
        };

        $scope.fetchSkosList = function () {
            skosService.listSkos().then(function (data) {
                $scope.skosList = _.map(data, $scope.decorateSkos);
            });
        };

        $scope.fetchSkosList();

        $scope.createSkos = function () {
            skosService.createSkos($scope.newDataset.spec).then(function () {
                $scope.cancelNewFile();
                $scope.newDataset.name = undefined;
                $scope.fetchSkosList();
            });
        };

        $scope.setDropSupported = function () {
            $scope.dropSupported = true;
        };
    };

    SkosListCtrl.$inject = ["$rootScope", "$scope", "$location", "$routeParams", "skosService", "user"];

    var metadataFields = [
        "skosName", "skosOwner"
    ];

    var SkosListEntryCtrl = function ($scope, skosService, $location, $timeout, $upload) {

        var sk = $scope.skos;

        $scope.tabOpen = "metadata";
        $scope.expanded = false;

        if (!sk) return;

        $scope.$watch("skos", function (newSk) {
            sk = newSk;
            setUnchanged()
        });

        function fileDropped($files, after) {
            //$files: an array of files selected, each file has name, size, and type.  Take the first only.
            if (!($files.length && !sk.uploading)) return;
            var onlyFile = $files[0];
            if (!(onlyFile.name.endsWith('.xml'))) {
                alert("Sorry, the file must end with '.xml'");
                return;
            }
            sk.uploading = true;
            $upload.upload({
                url: '/narthex/app/skos/' + sk.skosSpec + '/upload',
                file: onlyFile
            }).progress(
                function (evt) {
                    if (sk.uploading) sk.uploadPercent = parseInt(100.0 * evt.loaded / evt.total);
                }
            ).success(
                function () {
                    sk.uploading = false;
                    sk.uploadPercent = null;
                    if (after) after();
                }
            ).error(
                function (data, status, headers, config) {
                    sk.uploading = false;
                    sk.uploadPercent = null;
                    console.log("Failure during upload: data", data);
                    console.log("Failure during upload: status", status);
                    sk.error = data.problem;
                }
            );
        }

        function getStatistics() {
            skosService.skosStatistics(sk.skosSpec).then(function (statistics) {
                $scope.statistics = statistics;
            });
        }

        getStatistics();

        sk.fileDropped = function ($files) {
            fileDropped($files, function() {
                getStatistics();
                refreshInfo();
            });
        };

        function unchanged(fieldNameList) {
            var unchanged = true;
            _.forEach(fieldNameList, function (fieldName) {
                if (!angular.equals(sk[fieldName], sk.edit[fieldName])) {
//                    console.log("changed " + fieldName);
                    unchanged = false;
                }
            });
            return unchanged;
        }

        function setUnchanged() {
            $scope.unchangedMetadata = unchanged(metadataFields);
        }

        $scope.$watch("skos.edit", setUnchanged, true);

        function refreshInfo() {
            skosService.skosInfo(sk.skosSpec).then(function (skos) {
                $scope.skos = $scope.decorateSkos(skos);
            });
        }

        function setProperties(propertyList) {
            var payload = {propertyList: propertyList, values: {}};
            _.forEach(propertyList, function (propertyName) {
                payload.values[propertyName] = angular.copy(sk.edit[propertyName]);
            });
            skosService.setProperties(sk.skosSpec, payload).then(refreshInfo);
        }

        $scope.setMetadata = function () {
            setProperties(metadataFields);
        };

    };

    SkosListEntryCtrl.$inject = ["$scope", "skosService", "$location", "$timeout", "$upload"];

    var SkosChooseCtrl = function ($rootScope, $scope, $location, $routeParams, skosService, user) {
        if (user == null) $location.path("/");
        $scope.conceptSchemes = {};
        $scope.buttonEnabled = false;

        skosService.listConceptSchemes().then(function (data) {
            $scope.conceptSchemeList = data.list;
        });

        $scope.goToMapping = function () {
            if (!($scope.conceptSchemes.a && $scope.conceptSchemes.b)) return;
            $location.path('/skos/' + $scope.conceptSchemes.a + "/" + $scope.conceptSchemes.b);
        };

        $scope.$watch("conceptSchemes", function (schemes) {
            if (schemes.a && schemes.b) {
                $scope.buttonText = schemes.a + " <=> " + schemes.b;
                $scope.buttonEnabled = true;
            }
            else {
                $scope.buttonText = "Select two concept schemes";
                $scope.buttonEnabled = false;
            }
        }, true);
    };

    SkosChooseCtrl.$inject = ["$rootScope", "$scope", "$location", "$routeParams", "skosService", "user"];

    var SkosMapCtrl = function ($rootScope, $scope, $location, $routeParams, skosService, $timeout, pageScroll, user) {
        if (user == null) $location.path("/");
        var a = $routeParams.conceptSchemeA;
        var b = $routeParams.conceptSchemeB;
        $scope.conceptSchemeA = (a < b) ? a : b;
        $scope.conceptSchemeB = (a < b) ? b : a;
        $scope.show = "all";

        $scope.downloadUrl = user.narthexAPI + '/skos/' + $scope.conceptSchemeA + '/' + $scope.conceptSchemeB + '/mappings';
        $scope.mappingsAB = {};
        $scope.mappingsBA = {};

        $scope.sought = "";
        $scope.soughtA = "";
        $scope.soughtB = "";
        var fetchedConceptsA = [];
        var fetchedConceptsB = [];
        $scope.conceptsA = [];
        $scope.conceptsB = [];

        function filterConceptsNow() {
            switch ($scope.show) {
                case "all":
                    if ($scope.conceptA)
                        $scope.conceptsA = [ $scope.conceptA ];
                    else
                        $scope.conceptsA = fetchedConceptsA;
                    if ($scope.conceptB)
                        $scope.conceptsB = [ $scope.conceptB ];
                    else
                        $scope.conceptsB = fetchedConceptsB;
                    break;
                case "mapped":
                    if ($scope.conceptA)
                        $scope.conceptsA = [ $scope.conceptA ];
                    else
                        $scope.conceptsA = _.filter(fetchedConceptsA, function (c) {
                            return $scope.mappingsAB[c.uri];
                        });
                    if ($scope.conceptB)
                        $scope.conceptsB = [ $scope.conceptB ];
                    else
                        $scope.conceptsB = _.filter(fetchedConceptsB, function (c) {
                            return $scope.mappingsBA[c.uri];
                        });
                    break;
                case "unmapped":
                    if ($scope.conceptA)
                        $scope.conceptsA = [ $scope.conceptA ];
                    else
                        $scope.conceptsA = _.filter(fetchedConceptsA, function (c) {
                            return !$scope.mappingsAB[c.uri];
                        });
                    if ($scope.conceptB)
                        $scope.conceptsB = [ $scope.conceptB ];
                    else
                        $scope.conceptsB = _.filter(fetchedConceptsB, function (c) {
                            return !$scope.mappingsBA[c.uri];
                        });
                    break;
            }
        }

        var filterConceptsPromise;

        function filterConcepts() {
            $timeout.cancel(filterConceptsPromise);
            filterConceptsPromise = $timeout(filterConceptsNow, 500);
        }

        $scope.$watch("show", function (show) {
            filterConcepts();
        });

        function fetchMappings() {
            skosService.getThesaurusMappings($scope.conceptSchemeA, $scope.conceptSchemeB).then(function (data) {
                $scope.mappingsAB = {};
                $scope.mappingsBA = {};
                _.forEach(data.mappings, function (m) {
                    $scope.mappingsAB[m.uriA] = m.uriB;
                    $scope.mappingsBA[m.uriB] = m.uriA;
                });
            });
        }

        fetchMappings();

        $scope.scrollTo = function (options) {
            pageScroll.scrollTo(options);
        };

        function searchANow(value) {
//            $scope.scrollTo({element: '#skos-term-list-a', direction: 'up'});
            skosService.searchConceptScheme($scope.conceptSchemeA, value).then(function (data) {
                fetchedConceptsA = data.search.results;
                $scope.conceptA = null;
                filterConceptsNow();
            });
        }

        var searchAPromise;

        function searchA(value) {
            $timeout.cancel(searchAPromise);
            searchAPromise = $timeout(function () {
                searchANow(value);
            }, 500);
        }

        function searchBNow(value) {
//            $scope.scrollTo({element: '#skos-term-list-b', direction: 'up'});
            skosService.searchConceptScheme($scope.conceptSchemeB, value).then(function (data) {
                fetchedConceptsB = data.search.results;
                $scope.conceptB = null;
                filterConceptsNow();
            });
        }

        var searchBPromise;

        function searchB(value) {
            $timeout.cancel(searchBPromise);
            searchBPromise = $timeout(function () {
                searchBNow(value);
            }, 500);
        }

        $scope.selectSoughtA = function (value) {
            $scope.soughtA = value;
        };

        $scope.selectSoughtB = function (value) {
            $scope.soughtB = value;
        };

        $scope.$watch("sought", function (sought) {
            $scope.soughtA = $scope.soughtB = sought;
        });

        $scope.$watch("soughtA", function (soughtA) {
            if (!soughtA) soughtA = "-";
            searchA(soughtA);
        });

        $scope.$watch("soughtB", function (soughtB) {
            if (!soughtB) soughtB = "-";
            searchB(soughtB);
        });

        function afterSelect() {
            if ($scope.conceptA && $scope.conceptB) {
                $scope.buttonText = "'" + $scope.conceptA.prefLabel + "' <= exact match => '" + $scope.conceptB.prefLabel + "'";
                $scope.buttonEnabled = true;
            }
            else {
                if ($scope.conceptA) {
                    var uriB = $scope.mappingsAB[$scope.conceptA.uri];
                    if (uriB) {
                        $scope.selectConceptB(_.find($scope.conceptsB, function (b) {
                            return b.uri == uriB;
                        }));
                        return
                    }
                }
                else if ($scope.conceptB) {
                    var uriA = $scope.mappingsBA[$scope.conceptB.uri];
                    if (uriA) {
                        $scope.selectConceptA(_.find($scope.conceptsA, function (a) {
                            return a.uri == uriA;
                        }));
                        return
                    }
                }
                $scope.buttonText = "Select two concepts";
                $scope.buttonEnabled = false;
            }
            filterConcepts();
        }

        afterSelect();

        $scope.selectConceptA = function (c) {
            $scope.conceptA = ($scope.conceptA == c) ? null : c;
            if ($scope.conceptA) $scope.conceptsA = [ $scope.conceptA ];
            afterSelect();
        };

        $scope.selectConceptB = function (c) {
            $scope.conceptB = ($scope.conceptB == c) ? null : c;
            if ($scope.conceptB) $scope.conceptsB = [ $scope.conceptB ];
            afterSelect();
        };

        $scope.toggleMapping = function () {
            var body = {
                uriA: $scope.conceptA.uri,
                uriB: $scope.conceptB.uri
            };
            skosService.setThesaurusMapping($scope.conceptSchemeA, $scope.conceptSchemeB, body).then(function (reply) {
                switch (reply.action) {
                    case "added":
                        $scope.mappingsAB[body.uriA] = body.uriB;
                        $scope.mappingsBA[body.uriB] = body.uriA;
                        break;
                    case "removed":
                        $scope.mappingsAB[body.uriA] = $scope.mappingsBA[body.uriB] = undefined;
                        break;
                }
                $scope.conceptA = $scope.conceptB = null;
                afterSelect();
            });
        };
    };

    SkosMapCtrl.$inject = ["$rootScope", "$scope", "$location", "$routeParams", "thesaurusService", "$timeout", "pageScroll", "user"];

    return {
        SkosListCtrl: SkosListCtrl,
        SkosListEntryCtrl: SkosListEntryCtrl,
        SkosChooseCtrl: SkosChooseCtrl,
        SkosMapCtrl: SkosMapCtrl
    };
});
