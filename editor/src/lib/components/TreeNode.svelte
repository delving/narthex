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
		fullPath?: string; // Full path for tooltip (e.g., /edm:RDF/ore:Aggregation/@rdf:resource)
	}

	interface Props {
		node: TreeNode;
		depth?: number;
		selectedId?: string | null;
		treeType: 'source' | 'target';
		onSelect?: (node: TreeNode) => void;
		onMappingClick?: (mappingId: string) => void;
		onAddClick?: (node: TreeNode, treeType: 'source' | 'target') => void;
		searchQuery?: string;
		forceExpand?: boolean;
		// Keyboard navigation props (optional - for parent-controlled expansion)
		highlightedId?: string | null;
		expandedNodes?: Set<string>;
		onToggleExpand?: (nodeId: string) => void;
		// Compact mode props (for target tree)
		compactMode?: boolean;
		isDragging?: boolean;
	}

	let {
		node,
		depth = 0,
		selectedId = null,
		treeType,
		onSelect,
		onMappingClick,
		onAddClick,
		searchQuery = '',
		forceExpand = false,
		highlightedId = null,
		expandedNodes,
		onToggleExpand,
		compactMode = false,
		isDragging = false
	}: Props = $props();

	// Auto-expand first 2 levels, or force expand when searching
	// When parent provides expandedNodes, use that; otherwise fall back to local state
	let manualExpanded = $state(depth < 2);
	const expanded = $derived(
		forceExpand ? true : (expandedNodes ? expandedNodes.has(node.id) : manualExpanded)
	);
	let isDragOver = $state(false);

	// Keyboard navigation highlight
	const isKeyboardHighlighted = $derived(highlightedId === node.id);

	const hasChildren = $derived(node.children && node.children.length > 0);

	// Compact mode: count attribute children for indicator
	const attributeChildren = $derived(
		node.children?.filter(c => c.isAttribute) || []
	);
	const nonAttributeChildren = $derived(
		node.children?.filter(c => !c.isAttribute) || []
	);
	const hasHiddenAttributes = $derived(
		compactMode && !expanded && attributeChildren.length > 0
	);

	// Check if this node matches the search query
	const matchesSearch = $derived(
		searchQuery && node.name.toLowerCase().includes(searchQuery.toLowerCase())
	);
	const isSelected = $derived(selectedId === node.id);
	const paddingLeft = $derived(depth * 16 + 8);

	// Track mappings from store - use $state for proper reactivity in Svelte 5
	let storeMappings = $state<import('$lib/stores/mappingStore').Mapping[]>([]);
	let currentDragState = $state<import('$lib/stores/mappingStore').DragState>({
		isDragging: false,
		sourceType: null,
		draggedNode: null
	});

	// Subscribe to stores using onMount for reliability
	onMount(() => {
		const unsubMappings = mappingsStore.subscribe(mappings => {
			storeMappings = mappings;
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
				targetId: m.targetId,
				fullPath: m.targetPath // Include full target path for tooltip
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
				sourceId: m.sourceId,
				fullPath: m.sourcePath // Include full source path for tooltip
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

	// Check if field has values (completeness > 0) or is always empty
	const hasNoValues = $derived(
		node.quality && node.quality.completeness === 0
	);
	const hasLowCompleteness = $derived(
		node.quality && node.quality.completeness > 0 && node.quality.completeness < 50
	);

	// Helper: check if all children are orphans (recursive)
	function hasOnlyOrphanChildren(n: TreeNode): boolean {
		if (!n.children || n.children.length === 0) return true;
		return n.children.every(child => child.isOrphan && hasOnlyOrphanChildren(child));
	}

	// Orphan styling should only apply when:
	// 1. Node is marked as orphan AND
	// 2. Either has no children OR all children are also orphans
	const showOrphanStyle = $derived(
		node.isOrphan && hasOnlyOrphanChildren(node)
	);

	// Generate tooltip text for nodes
	const nodeTooltip = $derived.by(() => {
		const parts: string[] = [];

		parts.push(`Tree path: ${node.path}`);

		if (node.mappingPath && node.mappingPath !== node.path) {
			parts.push(`Mapping path: ${node.mappingPath}`);
		}

		if (node.isOrphan) {
			parts.push('⚠ This path exists in the mapping but not in the source data');
		}

		if (node.isConstant) {
			parts.push('Drag to target field to map a constant value');
		}

		if (node.isFact && node.factValue) {
			parts.push(`Fact value: ${node.factValue}`);
		}

		if (node.quality) {
			parts.push(`Completeness: ${node.quality.completeness.toFixed(1)}%`);
		}

		if (node.count !== undefined) {
			parts.push(`Count: ${node.count.toLocaleString()}`);
		}

		return parts.join('\n');
	});

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

	// Click on "+" button to open mapping modal
	function handleAddClick(e: MouseEvent) {
		e.stopPropagation();
		onAddClick?.(node, treeType);
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
		if (onToggleExpand) {
			onToggleExpand(node.id);
		} else {
			manualExpanded = !manualExpanded;
		}
	}

	function select() {
		onSelect?.(node);
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Enter' || e.key === ' ') {
			e.preventDefault();
			select();
		} else if (e.key === 'ArrowRight' && hasChildren && !expanded) {
			e.preventDefault();
			if (onToggleExpand) {
				onToggleExpand(node.id);
			} else {
				manualExpanded = true;
			}
		} else if (e.key === 'ArrowLeft' && expanded) {
			e.preventDefault();
			if (onToggleExpand) {
				onToggleExpand(node.id);
			} else {
				manualExpanded = false;
			}
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
			// Auto-expand on drag over in compact mode (so user can drop on children)
			if (compactMode && hasChildren && !expanded && onToggleExpand) {
				onToggleExpand(node.id);
			}
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
		class:orphan={showOrphanStyle}
		class:orphan-mapped={node.isOrphan && hasMappings}
		class:dragging={isBeingDragged}
		class:drag-over={isDragOver && canAcceptDrop}
		class:can-drop={canAcceptDrop && currentDragState.isDragging}
		class:keyboard-highlight={isKeyboardHighlighted}
		style="padding-left: {paddingLeft}px"
		title={nodeTooltip}
		onclick={select}
		onkeydown={handleKeydown}
		role="treeitem"
		tabindex="0"
		aria-expanded={hasChildren ? expanded : undefined}
		aria-selected={isSelected}
		data-node-id={node.id}
		data-node-path={node.path}
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

		<!-- Add mapping button -->
		{#if onAddClick}
			<button
				type="button"
				class="add-btn"
				onclick={handleAddClick}
				title={treeType === 'source' ? 'Map to target field...' : 'Map from source field...'}
				aria-label="Add mapping"
			>
				<svg viewBox="0 0 20 20" fill="currentColor">
					<path d="M10 5a1 1 0 011 1v3h3a1 1 0 110 2h-3v3a1 1 0 11-2 0v-3H6a1 1 0 110-2h3V6a1 1 0 011-1z"/>
				</svg>
			</button>
		{/if}

		<!-- Node icon -->
		{#if node.isConstant}
			<span class="node-icon constant" title="Drag to target field to enter a constant value">"</span>
		{:else if node.isFacts}
			<span class="node-icon facts" title="Mapping facts - expand to see values">≡</span>
		{:else if node.isFact}
			<span class="node-icon fact" title="Drag to target field to use this fact value">$</span>
		{:else if node.isOrphan}
			<span class="node-icon orphan" title="Orphaned field - no longer in source data">⚠</span>
		{:else if node.isAttribute}
			<span class="node-icon attribute">@</span>
		{:else}
			<span class="node-icon element">&lt;&gt;</span>
		{/if}

		<!-- Node name - orphan names stay red even when mapped to indicate no output possible -->
		<span
			class="node-name"
			class:highlight={matchesSearch}
			class:no-values={hasNoValues}
			class:low-completeness={hasLowCompleteness}
			class:orphan={node.isOrphan}
			class:qualified-variant={node.isQualifiedVariant}
		>
			{#if searchQuery && matchesSearch}
				{@const lowerName = node.name.toLowerCase()}
				{@const lowerQuery = searchQuery.toLowerCase()}
				{@const startIdx = lowerName.indexOf(lowerQuery)}
				{node.name.slice(0, startIdx)}<mark>{node.name.slice(startIdx, startIdx + searchQuery.length)}</mark>{node.name.slice(startIdx + searchQuery.length)}
			{:else}
				{node.name}
			{/if}
		</span>

		<!-- Qualifier label for qualified variants -->
		{#if node.qualifier}
			<span class="qualifier-label" title={`Qualified variant: ${node.qualifier}`}>
				[{node.qualifier}]
			</span>
		{/if}

		<!-- Fact value (for fact nodes) -->
		{#if node.isFact && node.factValue}
			<span class="fact-value" title={node.factValue}>: {node.factValue}</span>
		{/if}

		<!-- Attribute count badge (compact mode) -->
		{#if hasHiddenAttributes}
			<span class="attr-count-badge" title={`${attributeChildren.length} attribute${attributeChildren.length === 1 ? '' : 's'}: ${attributeChildren.map(a => a.name).join(', ')}`}>
				@{attributeChildren.length}
			</span>
		{/if}

		<!-- Empty field indicator -->
		{#if hasNoValues}
			<span class="empty-indicator" title="This field never has values">
				<svg viewBox="0 0 20 20" fill="currentColor">
					<path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.28 7.22a.75.75 0 00-1.06 1.06L8.94 10l-1.72 1.72a.75.75 0 101.06 1.06L10 11.06l1.72 1.72a.75.75 0 101.06-1.06L11.06 10l1.72-1.72a.75.75 0 00-1.06-1.06L10 8.94 8.28 7.22z" clip-rule="evenodd"/>
				</svg>
			</span>
		{/if}

		<!-- Mapping badges - show first + count, expand on hover -->
		<!-- If source is orphan, badges are red to indicate no output will be produced -->
		{#if allMappedTo.length > 0}
			{@const firstMapping = allMappedTo[0]}
			{@const extraCount = allMappedTo.length - 1}
			{@const allMappingsTooltip = allMappedTo.map(m => `→ ${m.fullPath || m.field}${m.label ? ` (${m.label})` : ''}`).join('\n')}
			<span class="mapping-badges to" class:has-more={extraCount > 0} class:orphan-source={node.isOrphan} title={extraCount > 0 ? allMappingsTooltip : undefined}>
				<!-- First badge always visible -->
				<span
					class="mapping-badge to"
					class:clickable={firstMapping.removable && onMappingClick}
					class:removable={firstMapping.removable}
					title={firstMapping.fullPath || `Maps to ${firstMapping.field}`}
					onclick={(e) => handleMappingClick(firstMapping, e)}
					onkeydown={(e) => { if (e.key === 'Enter') handleMappingClick(firstMapping, e as unknown as MouseEvent); }}
					role={firstMapping.removable ? 'button' : undefined}
					tabindex={firstMapping.removable ? 0 : undefined}
				>
					<span class="arrow">→</span>
					<span class="field">{firstMapping.field}</span>
					{#if firstMapping.label}
						<span class="label">{firstMapping.label}</span>
					{/if}
					{#if firstMapping.removable}
						<button
							type="button"
							class="remove-btn"
							title="Remove mapping"
							onclick={(e) => removeMapping(firstMapping, e)}
							aria-label="Remove mapping"
						>×</button>
					{/if}
				</span>
				<!-- Show +N for additional mappings -->
				{#if extraCount > 0}
					<span class="mapping-count">+{extraCount}</span>
				{/if}
				<!-- Expanded badges shown on hover -->
				{#each allMappedTo.slice(1) as mapping}
					<span
						class="mapping-badge to extra"
						class:clickable={mapping.removable && onMappingClick}
						class:removable={mapping.removable}
						title={mapping.fullPath || `Maps to ${mapping.field}`}
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
			{@const firstFromMapping = allMappedFrom[0]}
			{@const extraFromCount = allMappedFrom.length - 1}
			{@const allMappingsFromTooltip = allMappedFrom.map(m => `← ${m.fullPath || m.field}${m.label ? ` (${m.label})` : ''}`).join('\n')}
			<span class="mapping-badges from" class:has-more={extraFromCount > 0} title={extraFromCount > 0 ? allMappingsFromTooltip : undefined}>
				<!-- First badge always visible -->
				<span
					class="mapping-badge from"
					class:clickable={firstFromMapping.removable && onMappingClick}
					class:removable={firstFromMapping.removable}
					title={firstFromMapping.fullPath || `Mapped from ${firstFromMapping.field}`}
					onclick={(e) => handleMappingClick(firstFromMapping, e)}
					onkeydown={(e) => { if (e.key === 'Enter') handleMappingClick(firstFromMapping, e as unknown as MouseEvent); }}
					role={firstFromMapping.removable ? 'button' : undefined}
					tabindex={firstFromMapping.removable ? 0 : undefined}
				>
					<span class="arrow">←</span>
					<span class="field">{firstFromMapping.field}</span>
					{#if firstFromMapping.label}
						<span class="label">{firstFromMapping.label}</span>
					{/if}
					{#if firstFromMapping.removable}
						<button
							type="button"
							class="remove-btn"
							title="Remove mapping"
							onclick={(e) => removeMapping(firstFromMapping, e)}
							aria-label="Remove mapping"
						>×</button>
					{/if}
				</span>
				<!-- Show +N for additional mappings -->
				{#if extraFromCount > 0}
					<span class="mapping-count">+{extraFromCount}</span>
				{/if}
				<!-- Expanded badges shown on hover -->
				{#each allMappedFrom.slice(1) as mapping}
					<span
						class="mapping-badge from extra"
						class:clickable={mapping.removable && onMappingClick}
						class:removable={mapping.removable}
						title={mapping.fullPath || `Mapped from ${mapping.field}`}
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
					{onAddClick}
					{searchQuery}
					{forceExpand}
					{highlightedId}
					{expandedNodes}
					{onToggleExpand}
					{compactMode}
					{isDragging}
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

	.tree-node.keyboard-highlight {
		background-color: rgba(59, 130, 246, 0.15);
		outline: 2px solid #3b82f6;
		outline-offset: -2px;
	}

	.tree-node.keyboard-highlight.selected {
		background-color: #1e3a5f;
	}

	.tree-node.mapped .node-name {
		color: #4ade80;
	}

	/* Orphan nodes with mappings - keep text red to show no output possible */
	.tree-node.orphan-mapped .node-name {
		color: #ef4444;
	}

	/* Orphan nodes - fields that no longer exist in source data */
	.tree-node.orphan {
		background-color: rgba(239, 68, 68, 0.08);
		border-left: 2px solid #ef4444;
	}

	.tree-node.orphan:hover {
		background-color: rgba(239, 68, 68, 0.15);
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

	.add-btn {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 18px;
		height: 18px;
		padding: 0;
		border: none;
		background: transparent;
		color: #6b7280;
		border-radius: 4px;
		cursor: pointer;
		flex-shrink: 0;
		opacity: 0;
		transition: all 0.15s;
	}

	.add-btn svg {
		width: 14px;
		height: 14px;
	}

	.tree-node:hover .add-btn,
	.tree-node.selected .add-btn {
		opacity: 1;
	}

	.add-btn:hover {
		background: #3b82f6;
		color: white;
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

	.node-icon.orphan {
		color: #ef4444;
		background: rgba(239, 68, 68, 0.15);
		font-size: 11px;
	}

	.node-icon.constant {
		color: #a78bfa;
		background: rgba(167, 139, 250, 0.15);
		font-weight: bold;
		font-family: serif;
	}

	.node-icon.facts {
		color: #fbbf24;
		background: rgba(251, 191, 36, 0.15);
	}

	.node-icon.fact {
		color: #34d399;
		background: rgba(52, 211, 153, 0.15);
		font-weight: bold;
	}

	.fact-value {
		color: #9ca3af;
		font-size: 12px;
		font-style: italic;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		max-width: 200px;
		pointer-events: none;
	}

	/* Compact mode: collapsed attribute count indicator */
	.attr-count-badge {
		display: inline-flex;
		align-items: center;
		font-size: 9px;
		font-weight: 500;
		padding: 1px 5px;
		border-radius: 8px;
		background: rgba(244, 114, 182, 0.15);
		color: #f472b6;
		white-space: nowrap;
		flex-shrink: 0;
		cursor: help;
	}

	/* Qualifier label for qualified variants */
	.qualifier-label {
		display: inline-flex;
		align-items: center;
		font-size: 11px;
		font-weight: 500;
		padding: 0 4px;
		margin-left: 4px;
		border-radius: 4px;
		background: rgba(147, 51, 234, 0.2);
		color: #c084fc;
		white-space: nowrap;
		flex-shrink: 0;
	}

	/* Qualified variant node styling */
	.node-name.qualified-variant {
		color: #c084fc;
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

	.node-name.no-values {
		color: #6b7280;
		font-style: italic;
	}

	.node-name.low-completeness {
		color: #fbbf24;
	}

	/* Orphan nodes - source paths that no longer exist in the data */
	/* Base orphan styling (when no valid children) */
	.tree-node.orphan {
		background-color: rgba(239, 68, 68, 0.08);
		border-left: 2px solid #ef4444;
	}

	/* Orphan nodes that are also mapped - red text to show no output possible */
	.tree-node.orphan-mapped {
		color: #ef4444;
	}

	.tree-node.orphan-mapped .count-badge {
		background: rgba(239, 68, 68, 0.15);
		color: #ef4444;
	}

	.tree-node.orphan-mapped .node-icon {
		color: #ef4444;
	}

	.node-name.orphan {
		color: #ef4444;
		font-style: italic;
		text-decoration: line-through;
		text-decoration-color: #ef4444;
	}

	.node-name mark {
		background: rgba(250, 204, 21, 0.4);
		color: inherit;
		padding: 0 1px;
		border-radius: 2px;
	}

	.empty-indicator {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 14px;
		height: 14px;
		color: #6b7280;
		flex-shrink: 0;
	}

	.empty-indicator svg {
		width: 12px;
		height: 12px;
	}

	.mapping-badges {
		display: flex;
		flex-wrap: nowrap;
		gap: 3px;
		flex-shrink: 1;
		min-width: 0;
		align-items: center;
	}

	/* Hide extra badges by default */
	.mapping-badge.extra {
		display: none;
	}

	/* Show extra badges on hover and allow wrapping */
	.mapping-badges.has-more:hover {
		flex-wrap: wrap;
	}

	.mapping-badges.has-more:hover .mapping-badge.extra {
		display: inline-flex;
	}

	/* Hide the +N count on hover */
	.mapping-badges.has-more:hover .mapping-count {
		display: none;
	}

	/* +N count badge */
	.mapping-count {
		display: inline-flex;
		align-items: center;
		padding: 2px 5px;
		font-size: 9px;
		font-weight: 600;
		color: #9ca3af;
		background: #374151;
		border-radius: 8px;
		white-space: nowrap;
	}

	/* Orphan source badges - red to indicate no output will be produced */
	.mapping-badges.orphan-source .mapping-badge.to {
		background: rgba(239, 68, 68, 0.15);
		border-color: rgba(239, 68, 68, 0.3);
		color: #ef4444;
	}

	.mapping-badges.orphan-source .mapping-badge.to .arrow {
		color: #ef4444;
	}

	.mapping-badges.orphan-source .mapping-count {
		background: rgba(239, 68, 68, 0.2);
		color: #ef4444;
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
