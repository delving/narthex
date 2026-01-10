<script lang="ts">
	import type { TreeNode, MappingLink } from '$lib/types';
	import TreeNodeComponent from './TreeNode.svelte';
	import { dragStore, mappingsStore } from '$lib/stores/mappingStore';
	import { onMount } from 'svelte';
	import { get } from 'svelte/store';

	// Extended mapping link with removability info
	interface DisplayMapping extends MappingLink {
		removable: boolean;
		mappingId?: string;
		targetId?: string;
		sourceId?: string;
	}

	interface Props {
		node: TreeNode;
		depth?: number;
		selectedId?: string | null;
		treeType: 'source' | 'target';
		onSelect?: (node: TreeNode) => void;
		onMappingClick?: (mappingId: string) => void;
		searchQuery?: string;
		forceExpand?: boolean;
	}

	let { node, depth = 0, selectedId = null, treeType, onSelect, onMappingClick, searchQuery = '', forceExpand = false }: Props = $props();

	// Auto-expand first 2 levels, or force expand when searching
	let manualExpanded = $state(depth < 2);
	let expanded = $derived(forceExpand || manualExpanded);
	let isDragOver = $state(false);

	const hasChildren = $derived(node.children && node.children.length > 0);

	// Check if this node matches the search query
	const matchesSearch = $derived(
		searchQuery && node.name.toLowerCase().includes(searchQuery.toLowerCase())
	);
	const isSelected = $derived(selectedId === node.id);
	const paddingLeft = $derived(depth * 16 + 8);

	// Track mappings from store with version counter for reactivity
	let storeMappings: import('$lib/stores/mappingStore').Mapping[] = [];
	let mappingsVersion = $state(0);
	let currentDragState = $state<import('$lib/stores/mappingStore').DragState>({
		isDragging: false,
		sourceType: null,
		draggedNode: null
	});

	// Subscribe to stores using onMount for reliability
	onMount(() => {
		const unsubMappings = mappingsStore.subscribe(mappings => {
			storeMappings = mappings;
			mappingsVersion++; // Force reactivity by updating a tracked state
		});
		const unsubDrag = dragStore.subscribe(state => {
			currentDragState = state;
		});

		return () => {
			unsubMappings();
			unsubDrag();
		};
	});

	// Combine static mappings with dynamic ones from store
	const allMappedTo = $derived.by((): DisplayMapping[] => {
		// Reference mappingsVersion to track store changes
		const _v = mappingsVersion;

		const staticMappings: DisplayMapping[] = (node.mappedTo || []).map(m => ({
			...m,
			removable: false
		}));

		// Get dynamic mappings from store where this node is the source
		const dynamicMappings: DisplayMapping[] = storeMappings
			.filter(m => m.sourceId === node.id)
			.map(m => ({
				field: m.targetName,
				label: m.label,
				removable: true,
				mappingId: m.id,
				targetId: m.targetId
			}));

		// Merge, avoiding duplicates by field name
		const merged = [...staticMappings];
		for (const dm of dynamicMappings) {
			if (!merged.some(m => m.field === dm.field)) {
				merged.push(dm);
			}
		}

		return merged;
	});

	const allMappedFrom = $derived.by((): DisplayMapping[] => {
		// Reference mappingsVersion to track store changes
		const _v = mappingsVersion;

		const staticMappings: DisplayMapping[] = (node.mappedFrom || []).map(m => ({
			...m,
			removable: false
		}));

		// Get dynamic mappings from store where this node is the target
		const dynamicMappings: DisplayMapping[] = storeMappings
			.filter(m => m.targetId === node.id)
			.map(m => ({
				field: m.sourceName,
				label: m.label,
				removable: true,
				mappingId: m.id,
				sourceId: m.sourceId
			}));

		const merged = [...staticMappings];
		for (const dm of dynamicMappings) {
			if (!merged.some(m => m.field === dm.field)) {
				merged.push(dm);
			}
		}

		return merged;
	});

	const hasMappings = $derived(allMappedTo.length > 0 || allMappedFrom.length > 0);

	// Remove a mapping
	function removeMapping(mapping: DisplayMapping, e: MouseEvent) {
		e.stopPropagation();
		if (!mapping.removable || !mapping.mappingId) return;
		mappingsStore.removeMapping(mapping.mappingId);
	}

	// Click on a mapping badge to jump to tweak panel
	function handleMappingClick(mapping: DisplayMapping, e: MouseEvent) {
		e.stopPropagation();
		if (mapping.mappingId && onMappingClick) {
			onMappingClick(mapping.mappingId);
		}
	}

	// Can this node accept a drop?
	const canAcceptDrop = $derived.by(() => {
		if (!currentDragState.isDragging) return false;
		// Can only drop on opposite tree type
		return currentDragState.sourceType !== treeType;
	});

	// Is this node currently being dragged?
	const isBeingDragged = $derived(
		currentDragState.isDragging && currentDragState.draggedNode?.id === node.id
	);

	function toggle(e: MouseEvent) {
		e.stopPropagation();
		manualExpanded = !manualExpanded;
	}

	function select() {
		onSelect?.(node);
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Enter' || e.key === ' ') {
			e.preventDefault();
			select();
		} else if (e.key === 'ArrowRight' && hasChildren && !manualExpanded) {
			e.preventDefault();
			manualExpanded = true;
		} else if (e.key === 'ArrowLeft' && manualExpanded) {
			e.preventDefault();
			manualExpanded = false;
		}
	}

	// Drag handlers
	function handleDragStart(e: DragEvent) {
		if (!e.dataTransfer) return;

		e.dataTransfer.effectAllowed = 'link';
		e.dataTransfer.setData('text/plain', JSON.stringify({
			id: node.id,
			name: node.name,
			path: node.path,
			treeType
		}));

		// Set drag image
		const target = e.target as HTMLElement;
		e.dataTransfer.setDragImage(target, 0, 0);

		dragStore.startDrag(node, treeType);
	}

	function handleDragEnd() {
		// Get the last valid drag target from global state
		// (dragend coordinates are unreliable - they return source position, not cursor position)
		const lastTarget = (window as unknown as { __lastDragTarget?: { nodeId: string; treeType: string; nodeName: string } }).__lastDragTarget;

		if (lastTarget && lastTarget.treeType !== treeType) {
			// Dispatch custom event with the target info
			const customEvent = new CustomEvent('tree-drop', {
				bubbles: true,
				detail: {
					sourceNode: node,
					sourceTreeType: treeType,
					targetNodeId: lastTarget.nodeId,
					targetTreeType: lastTarget.treeType
				}
			});
			document.dispatchEvent(customEvent);
		}

		// Clear the stored target
		delete (window as unknown as { __lastDragTarget?: unknown }).__lastDragTarget;

		dragStore.endDrag();
		isDragOver = false;
	}

	function handleDragOver(e: DragEvent) {
		// Always prevent default to allow drop - we'll validate in handleDrop
		e.preventDefault();

		if (e.dataTransfer) {
			e.dataTransfer.dropEffect = canAcceptDrop ? 'link' : 'none';
		}

		// Store last drag position globally for use in dragend
		// (dragend coordinates are unreliable - they return source position, not cursor position)
		if (canAcceptDrop) {
			(window as unknown as { __lastDragTarget?: { nodeId: string; treeType: string; nodeName: string } }).__lastDragTarget = {
				nodeId: node.id,
				treeType: treeType,
				nodeName: node.name
			};
		}

		if (canAcceptDrop && !isDragOver) {
			isDragOver = true;
		}
	}

	function handleDragLeave() {
		isDragOver = false;
	}

	function handleDrop(e: DragEvent) {
		// Fallback drop handler (may not fire in all contexts due to PaneForge)
		e.preventDefault();
		e.stopPropagation();
		isDragOver = false;

		const dragState = get(dragStore);
		if (!dragState.draggedNode || dragState.sourceType === treeType) {
			return;
		}

		const draggedNode = dragState.draggedNode;

		// Determine source and target based on tree types
		if (treeType === 'target' && dragState.sourceType === 'source') {
			mappingsStore.addMapping(draggedNode, node);
		} else if (treeType === 'source' && dragState.sourceType === 'target') {
			mappingsStore.addMapping(node, draggedNode);
		}

		dragStore.endDrag();
	}
</script>

<div class="tree-node-container">
	<!-- Node row -->
	<div
		class="tree-node"
		class:selected={isSelected}
		class:mapped={hasMappings}
		class:dragging={isBeingDragged}
		class:drag-over={isDragOver && canAcceptDrop}
		class:can-drop={canAcceptDrop && currentDragState.isDragging}
		style="padding-left: {paddingLeft}px"
		onclick={select}
		onkeydown={handleKeydown}
		role="treeitem"
		tabindex="0"
		aria-expanded={hasChildren ? expanded : undefined}
		aria-selected={isSelected}
		data-node-id={node.id}
		data-tree-type={treeType}
		draggable="true"
		ondragstart={handleDragStart}
		ondragend={handleDragEnd}
		ondragover={handleDragOver}
		ondragleave={handleDragLeave}
		ondrop={handleDrop}
	>
		<!-- Expand/collapse toggle -->
		{#if hasChildren}
			<span
				class="toggle"
				onclick={toggle}
				onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') toggle(e as unknown as MouseEvent); }}
				role="button"
				tabindex="-1"
				aria-label={expanded ? 'Collapse' : 'Expand'}
			>
				<svg
					class="toggle-icon"
					class:expanded
					viewBox="0 0 20 20"
					fill="currentColor"
				>
					<path
						fill-rule="evenodd"
						d="M7.21 14.77a.75.75 0 01.02-1.06L11.168 10 7.23 6.29a.75.75 0 111.04-1.08l4.5 4.25a.75.75 0 010 1.08l-4.5 4.25a.75.75 0 01-1.06-.02z"
						clip-rule="evenodd"
					/>
				</svg>
			</span>
		{:else}
			<span class="toggle-placeholder"></span>
		{/if}

		<!-- Drag handle indicator -->
		<span class="drag-handle">
			<svg viewBox="0 0 20 20" fill="currentColor">
				<path d="M7 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM7 8a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM7 14a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM13 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM13 8a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM13 14a2 2 0 1 0 0 4 2 2 0 0 0 0-4z"/>
			</svg>
		</span>

		<!-- Node icon -->
		{#if node.isAttribute}
			<span class="node-icon attribute">@</span>
		{:else}
			<span class="node-icon element">&lt;&gt;</span>
		{/if}

		<!-- Node name -->
		<span class="node-name" class:highlight={matchesSearch}>
			{#if searchQuery && matchesSearch}
				{@const lowerName = node.name.toLowerCase()}
				{@const lowerQuery = searchQuery.toLowerCase()}
				{@const startIdx = lowerName.indexOf(lowerQuery)}
				{node.name.slice(0, startIdx)}<mark>{node.name.slice(startIdx, startIdx + searchQuery.length)}</mark>{node.name.slice(startIdx + searchQuery.length)}
			{:else}
				{node.name}
			{/if}
		</span>

		<!-- Mapping badges -->
		{#if allMappedTo.length > 0}
			<span class="mapping-badges to">
				{#each allMappedTo as mapping}
					<span
						class="mapping-badge to"
						class:clickable={mapping.removable && onMappingClick}
						class:removable={mapping.removable}
						title={mapping.removable ? `Click to edit in Tweak panel` : `Maps to ${mapping.field}`}
						onclick={(e) => handleMappingClick(mapping, e)}
						onkeydown={(e) => { if (e.key === 'Enter') handleMappingClick(mapping, e as unknown as MouseEvent); }}
						role={mapping.removable ? 'button' : undefined}
						tabindex={mapping.removable ? 0 : undefined}
					>
						<span class="arrow">→</span>
						<span class="field">{mapping.field}</span>
						{#if mapping.label}
							<span class="label">{mapping.label}</span>
						{/if}
						{#if mapping.removable}
							<button
								type="button"
								class="remove-btn"
								title="Remove mapping"
								onclick={(e) => removeMapping(mapping, e)}
								aria-label="Remove mapping"
							>×</button>
						{/if}
					</span>
				{/each}
			</span>
		{/if}

		{#if allMappedFrom.length > 0}
			<span class="mapping-badges from">
				{#each allMappedFrom as mapping}
					<span
						class="mapping-badge from"
						class:clickable={mapping.removable && onMappingClick}
						class:removable={mapping.removable}
						title={mapping.removable ? `Click to edit in Tweak panel` : `Mapped from ${mapping.field}`}
						onclick={(e) => handleMappingClick(mapping, e)}
						onkeydown={(e) => { if (e.key === 'Enter') handleMappingClick(mapping, e as unknown as MouseEvent); }}
						role={mapping.removable ? 'button' : undefined}
						tabindex={mapping.removable ? 0 : undefined}
					>
						<span class="arrow">←</span>
						<span class="field">{mapping.field}</span>
						{#if mapping.label}
							<span class="label">{mapping.label}</span>
						{/if}
						{#if mapping.removable}
							<button
								type="button"
								class="remove-btn"
								title="Remove mapping"
								onclick={(e) => removeMapping(mapping, e)}
								aria-label="Remove mapping"
							>×</button>
						{/if}
					</span>
				{/each}
			</span>
		{/if}

		<!-- Count badge -->
		{#if node.count !== undefined}
			<span class="count-badge">{node.count.toLocaleString()}</span>
		{/if}

		<!-- Drop indicator -->
		{#if isDragOver && canAcceptDrop}
			<span class="drop-indicator">
				{treeType === 'target' ? '← Drop to map' : 'Drop to map →'}
			</span>
		{/if}
	</div>

	<!-- Children -->
	{#if hasChildren && expanded}
		<div class="children" role="group">
			{#each node.children! as child (child.id)}
				<TreeNodeComponent
					node={child}
					depth={depth + 1}
					{selectedId}
					{treeType}
					{onSelect}
					{onMappingClick}
					{searchQuery}
					{forceExpand}
				/>
			{/each}
		</div>
	{/if}
</div>

<style>
	.tree-node-container {
		user-select: none;
	}

	.tree-node {
		display: flex;
		align-items: center;
		gap: 4px;
		width: 100%;
		padding: 4px 8px;
		border: none;
		background: transparent;
		color: inherit;
		font: inherit;
		text-align: left;
		cursor: grab;
		border-radius: 4px;
		transition: background-color 0.1s, border-color 0.1s, box-shadow 0.1s;
		outline: none;
		border: 2px solid transparent;
	}

	.tree-node:hover {
		background-color: var(--color-tree-hover, #1f2937);
	}

	.tree-node:focus-visible {
		outline: 2px solid #3b82f6;
		outline-offset: -2px;
	}

	.tree-node.selected {
		background-color: var(--color-tree-selected, #1e3a5f);
	}

	.tree-node.mapped .node-name {
		color: #4ade80;
	}

	/* Drag states */
	.tree-node.dragging {
		opacity: 0.5;
		cursor: grabbing;
	}

	.tree-node.can-drop {
		border-color: rgba(96, 165, 250, 0.3);
	}

	.tree-node.drag-over {
		background-color: rgba(59, 130, 246, 0.2);
		border-color: #3b82f6;
		box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.3);
	}

	.drag-handle {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 12px;
		height: 12px;
		color: #4b5563;
		flex-shrink: 0;
		opacity: 0;
		transition: opacity 0.15s;
		pointer-events: none;
	}

	.drag-handle svg {
		width: 10px;
		height: 10px;
	}

	.tree-node:hover .drag-handle {
		opacity: 1;
	}

	.toggle {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 16px;
		height: 16px;
		padding: 0;
		border: none;
		background: transparent;
		color: #6b7280;
		cursor: pointer;
		flex-shrink: 0;
	}

	.toggle:hover {
		color: #9ca3af;
	}

	.toggle-icon {
		width: 14px;
		height: 14px;
		transition: transform 0.15s ease;
	}

	.toggle-icon.expanded {
		transform: rotate(90deg);
	}

	.toggle-placeholder {
		width: 16px;
		flex-shrink: 0;
		pointer-events: none;
	}

	.node-icon {
		font-size: 10px;
		font-weight: 600;
		padding: 1px 3px;
		border-radius: 2px;
		flex-shrink: 0;
		pointer-events: none;
	}

	.node-icon.element {
		color: #60a5fa;
		background: rgba(96, 165, 250, 0.1);
	}

	.node-icon.attribute {
		color: #f472b6;
		background: rgba(244, 114, 182, 0.1);
	}

	.node-name {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-size: 13px;
		pointer-events: none;
	}

	.node-name.highlight {
		font-weight: 500;
	}

	.node-name mark {
		background: rgba(250, 204, 21, 0.4);
		color: inherit;
		padding: 0 1px;
		border-radius: 2px;
	}

	.mapping-badges {
		display: flex;
		gap: 3px;
		flex-shrink: 0;
	}

	.mapping-badge {
		display: inline-flex;
		align-items: center;
		gap: 2px;
		font-size: 10px;
		font-family: inherit;
		padding: 1px 5px;
		border-radius: 8px;
		white-space: nowrap;
		cursor: default;
		transition: all 0.15s ease;
	}

	.mapping-badge.to {
		background: rgba(74, 222, 128, 0.15);
		color: #4ade80;
		border: 1px solid rgba(74, 222, 128, 0.3);
	}

	.mapping-badge.from {
		background: rgba(96, 165, 250, 0.15);
		color: #60a5fa;
		border: 1px solid rgba(96, 165, 250, 0.3);
	}

	.mapping-badge.clickable {
		cursor: pointer;
	}

	.mapping-badge.clickable:hover {
		filter: brightness(1.2);
	}

	.mapping-badge .arrow {
		font-weight: bold;
		opacity: 0.7;
	}

	.mapping-badge .field {
		font-weight: 500;
	}

	.mapping-badge .label {
		font-size: 9px;
		opacity: 0.7;
		padding-left: 2px;
	}

	.mapping-badge .remove-btn {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 14px;
		height: 14px;
		margin-left: 2px;
		padding: 0;
		border: none;
		background: transparent;
		color: inherit;
		font-size: 12px;
		font-weight: bold;
		cursor: pointer;
		border-radius: 50%;
		opacity: 0.5;
		transition: all 0.15s;
	}

	.mapping-badge .remove-btn:hover {
		opacity: 1;
		background: rgba(239, 68, 68, 0.3);
		color: #f87171;
	}

	.count-badge {
		font-size: 10px;
		color: #6b7280;
		background: #374151;
		padding: 1px 6px;
		border-radius: 10px;
		flex-shrink: 0;
		margin-left: auto;
		pointer-events: none;
	}

	.drop-indicator {
		font-size: 11px;
		color: #3b82f6;
		font-weight: 500;
		margin-left: auto;
		padding: 2px 8px;
		background: rgba(59, 130, 246, 0.2);
		border-radius: 4px;
		animation: pulse 1s ease-in-out infinite;
		pointer-events: none; /* Don't intercept drag/drop events */
	}

	@keyframes pulse {
		0%, 100% { opacity: 1; }
		50% { opacity: 0.6; }
	}

	.children {
		/* Children container */
	}
</style>
