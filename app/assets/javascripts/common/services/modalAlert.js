/**
 * Modal alert service - replaces browser alert() with Bootstrap modal
 */
define(["angular"], function (angular) {
    "use strict";

    var mod = angular.module("common.modalAlert", []);

    mod.service("modalAlert", ["$rootScope", "$timeout", function ($rootScope, $timeout) {
        return {
            /**
             * Show an error modal
             * @param title - Modal title
             * @param message - Error message
             */
            error: function(title, message) {
                $rootScope.$broadcast("modalAlert:show", {
                    type: "error",
                    title: title || "Error",
                    message: message
                });
            },

            /**
             * Show a warning modal
             * @param title - Modal title
             * @param message - Warning message
             */
            warning: function(title, message) {
                $rootScope.$broadcast("modalAlert:show", {
                    type: "warning",
                    title: title || "Warning",
                    message: message
                });
            },

            /**
             * Show an info modal
             * @param title - Modal title
             * @param message - Info message
             */
            info: function(title, message) {
                $rootScope.$broadcast("modalAlert:show", {
                    type: "info",
                    title: title || "Information",
                    message: message
                });
            },

            /**
             * Show a confirmation modal with OK/Cancel buttons
             * @param title - Modal title
             * @param message - Confirmation message
             * @param onConfirm - Callback function to execute when OK is clicked
             * @param onCancel - Optional callback function to execute when Cancel is clicked
             */
            confirm: function(title, message, onConfirm, onCancel) {
                $rootScope.$broadcast("modalAlert:show", {
                    type: "confirm",
                    title: title || "Confirm",
                    message: message,
                    onConfirm: onConfirm,
                    onCancel: onCancel
                });
            }
        };
    }]);

    /**
     * Modal alert controller - handles displaying the modal
     */
    mod.controller("ModalAlertCtrl", ["$scope", "$timeout", function($scope, $timeout) {
        $scope.alertModal = {
            visible: false,
            type: "error",
            title: "",
            message: "",
            onConfirm: null,
            onCancel: null
        };

        $scope.$on("modalAlert:show", function(event, data) {
            $scope.alertModal = {
                visible: true,
                type: data.type || "error",
                title: data.title || "Alert",
                message: data.message || "",
                onConfirm: data.onConfirm || null,
                onCancel: data.onCancel || null
            };
        });

        $scope.closeAlertModal = function() {
            $scope.alertModal.visible = false;
        };

        $scope.confirmAlertModal = function() {
            $scope.alertModal.visible = false;
            if ($scope.alertModal.onConfirm) {
                $timeout(function() {
                    $scope.alertModal.onConfirm();
                }, 0);
            }
        };

        $scope.cancelAlertModal = function() {
            $scope.alertModal.visible = false;
            if ($scope.alertModal.onCancel) {
                $timeout(function() {
                    $scope.alertModal.onCancel();
                }, 0);
            }
        };
    }]);

    return mod;
});
