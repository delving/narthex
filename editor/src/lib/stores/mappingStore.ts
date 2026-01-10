import { writable, derived } from 'svelte/store';
import type { TreeNode, MappingLink } from '$lib/types';

// Drag state
export interface DragState {
	isDragging: boolean;
	sourceType: 'source' | 'target' | null;
	draggedNode: TreeNode | null;
}

// Create the drag state store
function createDragStore() {
	const { subscribe, set, update } = writable<DragState>({
		isDragging: false,
		sourceType: null,
		draggedNode: null
	});

	return {
		subscribe,
		startDrag: (node: TreeNode, sourceType: 'source' | 'target') => {
			set({
				isDragging: true,
				sourceType,
				draggedNode: node
			});
		},
		endDrag: () => {
			set({
				isDragging: false,
				sourceType: null,
				draggedNode: null
			});
		}
	};
}

export const dragStore = createDragStore();

// Mapping between source and target paths
export interface Mapping {
	id: string;
	sourcePath: string;
	sourceId: string;
	sourceName: string;
	targetPath: string;
	targetId: string;
	targetName: string;
	label?: string;
}

// Mappings store
function createMappingsStore() {
	const { subscribe, set, update } = writable<Mapping[]>([]);

	return {
		subscribe,
		addMapping: (
			sourceNode: TreeNode,
			targetNode: TreeNode,
			label?: string
		) => {
			update((mappings) => {
				// Check if mapping already exists
				const exists = mappings.some(
					(m) => m.sourcePath === sourceNode.path && m.targetPath === targetNode.path
				);
				if (exists) {
					return mappings;
				}

				const newMapping: Mapping = {
					id: `${sourceNode.id}-${targetNode.id}-${Date.now()}`,
					sourcePath: sourceNode.path,
					sourceId: sourceNode.id,
					sourceName: sourceNode.name,
					targetPath: targetNode.path,
					targetId: targetNode.id,
					targetName: targetNode.name,
					label
				};

				return [...mappings, newMapping];
			});
		},
		removeMapping: (id: string) => {
			update((mappings) => mappings.filter((m) => m.id !== id));
		},
		removeMappingByNodes: (sourceId: string, targetId: string) => {
			update((mappings) =>
				mappings.filter((m) => !(m.sourceId === sourceId && m.targetId === targetId))
			);
		},
		clear: () => set([]),
		set
	};
}

export const mappingsStore = createMappingsStore();

// Derived store: get mappings for a specific source node
export function getMappingsForSource(sourceId: string) {
	return derived(mappingsStore, ($mappings) =>
		$mappings.filter((m) => m.sourceId === sourceId)
	);
}

// Derived store: get mappings for a specific target node
export function getMappingsForTarget(targetId: string) {
	return derived(mappingsStore, ($mappings) =>
		$mappings.filter((m) => m.targetId === targetId)
	);
}

// Derived store: check if a node is mapped
export function isNodeMapped(nodeId: string, type: 'source' | 'target') {
	return derived(mappingsStore, ($mappings) => {
		if (type === 'source') {
			return $mappings.some((m) => m.sourceId === nodeId);
		} else {
			return $mappings.some((m) => m.targetId === nodeId);
		}
	});
}

// Generate MappingLink arrays for tree nodes
export function getMappedTo(nodeId: string) {
	return derived(mappingsStore, ($mappings): MappingLink[] => {
		return $mappings
			.filter((m) => m.sourceId === nodeId)
			.map((m) => ({ field: m.targetName, label: m.label }));
	});
}

export function getMappedFrom(nodeId: string) {
	return derived(mappingsStore, ($mappings): MappingLink[] => {
		return $mappings
			.filter((m) => m.targetId === nodeId)
			.map((m) => ({ field: m.sourceName, label: m.label }));
	});
}
