/**
 * Controller for the Activity History modal
 * Displays JSONL activity log with workflow grouping
 */
define(['angular'], function (angular) {
    'use strict';

    var ActivityModalCtrl = function($scope, $http, $uibModalInstance, spec, activityUrl) {

        $scope.spec = spec;
        $scope.loading = true;
        $scope.error = null;
        $scope.activities = [];
        $scope.sortReverse = true; // Newest first by default
        $scope.sortField = 'timestamp';

        // Load and parse the JSONL activity log
        $scope.loadActivityLog = function() {
            $http.get(activityUrl).then(
                function(response) {
                    // Parse JSONL (one JSON object per line)
                    var lines = response.data.trim().split('\n');
                    var entries = [];

                    lines.forEach(function(line) {
                        if (line.trim()) {
                            try {
                                entries.push(JSON.parse(line));
                            } catch (e) {
                                console.error('Error parsing JSONL line:', line, e);
                            }
                        }
                    });

                    // Group entries by workflow_id
                    $scope.activities = buildActivityTree(entries);
                    $scope.loading = false;
                },
                function(error) {
                    $scope.error = 'Failed to load activity log';
                    $scope.loading = false;
                    console.error('Error loading activity log:', error);
                }
            );
        };

        /**
         * Build hierarchical structure from flat JSONL entries
         * Groups workflow entries with their steps
         */
        function buildActivityTree(entries) {
            var workflows = {};
            var standalone = [];

            entries.forEach(function(entry) {
                if (entry.workflow_id) {
                    // This is part of a workflow
                    if (!workflows[entry.workflow_id]) {
                        workflows[entry.workflow_id] = {
                            id: entry.workflow_id,
                            trigger: 'automatic',
                            trigger_type: null,
                            status: null,
                            timestamp: null,
                            duration_seconds: null,
                            total_records: null,
                            operations: [],
                            steps: [],
                            expanded: false,
                            isWorkflow: true
                        };
                    }

                    var workflow = workflows[entry.workflow_id];

                    if (entry.operation) {
                        // This is a step within the workflow
                        workflow.steps.push(entry);
                    } else {
                        // This is the workflow start or complete entry
                        workflow.trigger_type = entry.trigger_type || workflow.trigger_type;
                        workflow.operations = entry.operations || workflow.operations;

                        if (entry.status === 'started') {
                            workflow.timestamp = entry.timestamp;
                        } else if (entry.status === 'completed' || entry.status === 'failed') {
                            workflow.status = entry.status;
                            workflow.duration_seconds = entry.duration_seconds;
                            workflow.total_records = entry.total_records;
                            workflow.errorMessage = entry.errorMessage;
                            workflow.failed_operation = entry.failed_operation;
                        }
                    }
                } else {
                    // Standalone operation (manual trigger)
                    standalone.push(entry);
                }
            });

            // Convert workflows object to array and sort steps
            var workflowArray = Object.values(workflows).map(function(wf) {
                wf.steps.sort(function(a, b) {
                    return new Date(a.timestamp) - new Date(b.timestamp);
                });
                // Use the first step's timestamp if workflow start timestamp is missing
                if (!wf.timestamp && wf.steps.length > 0) {
                    wf.timestamp = wf.steps[0].timestamp;
                }
                return wf;
            });

            // Combine and sort all activities by timestamp
            var combined = standalone.concat(workflowArray);
            combined.sort(function(a, b) {
                return new Date(b.timestamp) - new Date(a.timestamp);
            });

            return combined;
        }

        $scope.toggleWorkflow = function(workflow) {
            workflow.expanded = !workflow.expanded;
        };

        $scope.formatDuration = function(seconds) {
            if (!seconds) return '-';

            var mins = Math.floor(seconds / 60);
            var secs = seconds % 60;

            if (mins > 0) {
                return mins + 'm ' + secs + 's';
            } else {
                return secs + 's';
            }
        };

        $scope.formatTimestamp = function(timestamp) {
            if (!timestamp) return '-';
            var date = new Date(timestamp);
            return date.toLocaleString();
        };

        $scope.getStatusClass = function(status) {
            switch(status) {
                case 'completed': return 'label-success';
                case 'failed': return 'label-danger';
                case 'started': return 'label-info';
                case 'in_progress': return 'label-warning';
                default: return 'label-default';
            }
        };

        $scope.getTriggerLabel = function(activity) {
            if (activity.isWorkflow) {
                return 'Automatic (' + (activity.trigger_type || 'periodic') + ')';
            } else {
                return activity.trigger === 'automatic' ? 'Automatic' : 'Manual';
            }
        };

        $scope.getOperationLabel = function(activity) {
            if (activity.isWorkflow) {
                return 'Workflow: ' + (activity.operations || []).join(' → ');
            } else {
                return activity.operation;
            }
        };

        $scope.close = function() {
            $uibModalInstance.dismiss('cancel');
        };

        // Load data on controller init
        $scope.loadActivityLog();
    };

    return ['$scope', '$http', '$uibModalInstance', 'spec', 'activityUrl', ActivityModalCtrl];
});
