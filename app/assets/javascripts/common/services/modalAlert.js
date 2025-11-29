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
            }
        };
    }]);

    /**
     * Modal alert controller - handles displaying the modal
     */
    mod.controller("ModalAlertCtrl", ["$scope", function($scope) {
        $scope.alertModal = {
            visible: false,
            type: "error",
            title: "",
            message: ""
        };

        $scope.$on("modalAlert:show", function(event, data) {
            $scope.alertModal = {
                visible: true,
                type: data.type || "error",
                title: data.title || "Alert",
                message: data.message || ""
            };
        });

        $scope.closeAlertModal = function() {
            $scope.alertModal.visible = false;
        };
    }]);

    return mod;
});
