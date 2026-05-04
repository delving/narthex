//===========================================================================
//    Copyright 2026 Delving B.V.
//===========================================================================

define(["angular"], function () {
    "use strict";

    var RecDefsListCtrl = function ($scope, recDefsService, modalAlert) {

        $scope.summaries = [];
        $scope.expandedPrefix = null;
        $scope.versions = {};                  // prefix -> { currentVersion, versions: [...] }
        $scope.uploadFiles = {};               // prefix -> { recdef: File, xsd: File, notes: string }

        $scope.refresh = function () {
            recDefsService.listRecDefs().then(function (data) {
                $scope.summaries = data || [];
            });
        };

        $scope.toggleExpand = function (prefix) {
            if ($scope.expandedPrefix === prefix) {
                $scope.expandedPrefix = null;
            } else {
                $scope.expandedPrefix = prefix;
                $scope.loadVersions(prefix);
            }
        };

        $scope.loadVersions = function (prefix) {
            recDefsService.listRecDefVersions(prefix).then(function (data) {
                $scope.versions[prefix] = data;
            });
        };

        $scope.onRecDefFileSelect = function (prefix, files) {
            if (!$scope.uploadFiles[prefix]) $scope.uploadFiles[prefix] = {};
            $scope.uploadFiles[prefix].recdef = files[0];
            $scope.$apply();
        };

        $scope.onXsdFileSelect = function (prefix, files) {
            if (!$scope.uploadFiles[prefix]) $scope.uploadFiles[prefix] = {};
            $scope.uploadFiles[prefix].xsd = files[0];
            $scope.$apply();
        };

        $scope.upload = function (prefix) {
            var u = $scope.uploadFiles[prefix];
            if (!u || !u.recdef) {
                modalAlert.error("Upload", "No record-definition file selected.");
                return;
            }
            recDefsService.uploadRecDef(prefix, u.recdef, u.xsd, u.notes).then(function () {
                $scope.uploadFiles[prefix] = {};
                $scope.loadVersions(prefix);
                $scope.refresh();
            });
        };

        $scope.uploadNew = function () {
            var prefix = $scope.newUploadPrefix;
            var u = $scope.newUpload || {};
            if (!prefix || !u.recdef) {
                modalAlert.error("Upload", "Prefix and record-definition file are required.");
                return;
            }
            recDefsService.uploadRecDef(prefix, u.recdef, u.xsd, u.notes).then(function () {
                $scope.newUpload = {};
                $scope.newUploadPrefix = "";
                $scope.refresh();
            });
        };

        $scope.onNewRecDefSelect = function (files) {
            if (!$scope.newUpload) $scope.newUpload = {};
            $scope.newUpload.recdef = files[0];
            $scope.$apply();
        };

        $scope.onNewXsdSelect = function (files) {
            if (!$scope.newUpload) $scope.newUpload = {};
            $scope.newUpload.xsd = files[0];
            $scope.$apply();
        };

        $scope.setCurrent = function (prefix, hash) {
            recDefsService.setCurrentRecDef(prefix, hash).then(function () {
                $scope.loadVersions(prefix);
                $scope.refresh();
            });
        };

        $scope.deleteVersion = function (prefix, hash) {
            modalAlert.confirm(
                "Delete rec-def version",
                "Permanently delete this version? This cannot be undone.",
                function () {
                    recDefsService.deleteRecDefVersion(prefix, hash).then(function () {
                        $scope.loadVersions(prefix);
                        $scope.refresh();
                    });
                }
            );
        };

        $scope.downloadXmlUrl = function (prefix, hash) {
            return '/narthex/app/rec-defs/' + prefix + '/' + hash + '/xml';
        };

        $scope.downloadXsdUrl = function (prefix, hash) {
            return '/narthex/app/rec-defs/' + prefix + '/' + hash + '/xsd';
        };

        $scope.refresh();
    };

    RecDefsListCtrl.$inject = ["$scope", "recDefsService", "modalAlert"];

    return {
        RecDefsListCtrl: RecDefsListCtrl
    };
});
