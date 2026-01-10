<script lang="ts">
	import type { TreeNode } from '$lib/types';
	import TreeNodeComponent from './TreeNode.svelte';

	interface Props {
		nodes: TreeNode[];
		selectedId?: string | null;
		treeType: 'source' | 'target';
		onSelect?: (node: TreeNode) => void;
		onMappingClick?: (mappingId: string) => void;
		onAddClick?: (node: TreeNode, treeType: 'source' | 'target') => void;
	}

	let { nodes, selectedId = null, treeType, onSelect, onMappingClick, onAddClick }: Props = $props();

	// Search state
	let searchQuery = $state('');
	let searchInputRef: HTMLInputElement;

	// Keyboard navigation state
	let highlightedIndex = $state(-1);
	let hasFocus = $state(false);

	// Expanded nodes tracking (managed at tree level for keyboard nav)
	let expandedNodes = $state<Set<string>>(new Set());

	// Initialize expanded nodes (first 2 levels)
	$effect(() => {
		if (expandedNodes.size === 0) {
			const initial = new Set<string>();
			for (const node of nodes) {
				initial.add(node.id);
				if (node.children) {
					for (const child of node.children) {
						initial.add(child.id);
					}
				}
			}
			expandedNodes = initial;
		}
	});

	// Check if node is expanded
	function isNodeExpanded(nodeId: string): boolean {
		if (searchQuery.trim()) return true; // Force expand when searching
		return expandedNodes.has(nodeId);
	}

	// Toggle node expansion
	function toggleNodeExpansion(nodeId: string) {
		const newSet = new Set(expandedNodes);
		if (newSet.has(nodeId)) {
			newSet.delete(nodeId);
		} else {
			newSet.add(nodeId);
		}
		expandedNodes = newSet;
	}

	// Clear search
	function clearSearchQuery() {
		searchQuery = '';
		highlightedIndex = -1;
	}

	// Check if a node or any of its descendants match the search
	function nodeMatchesSearch(node: TreeNode, query: string): boolean {
		const lowerQuery = query.toLowerCase();
		if (node.name.toLowerCase().includes(lowerQuery)) {
			return true;
		}
		if (node.children) {
			return node.children.some((child) => nodeMatchesSearch(child, lowerQuery));
		}
		return false;
	}

	// Filter nodes to only include those matching search
	function filterNodes(nodeList: TreeNode[], query: string): TreeNode[] {
		if (!query.trim()) return nodeList;

		const lowerQuery = query.toLowerCase();
		return nodeList
			.filter((node) => nodeMatchesSearch(node, lowerQuery))
			.map((node) => {
				if (node.children) {
					return {
						...node,
						children: filterNodes(node.children, query)
					};
				}
				return node;
			});
	}

	// Filtered nodes based on search query
	const filteredNodes = $derived(filterNodes(nodes, searchQuery));

	// Flatten visible tree for keyboard navigation
	function flattenVisibleTree(nodeList: TreeNode[]): TreeNode[] {
		const result: TreeNode[] = [];
		for (const node of nodeList) {
			result.push(node);
			if (node.children && isNodeExpanded(node.id)) {
				result.push(...flattenVisibleTree(node.children));
			}
		}
		return result;
	}

	const flattenedNodes = $derived(flattenVisibleTree(filteredNodes));

	// Count matching nodes
	function countMatches(nodeList: TreeNode[], query: string): number {
		if (!query.trim()) return 0;
		const lowerQuery = query.toLowerCase();
		let count = 0;
		for (const node of nodeList) {
			if (node.name.toLowerCase().includes(lowerQuery)) {
				count++;
			}
			if (node.children) {
				count += countMatches(node.children, query);
			}
		}
		return count;
	}

	const matchCount = $derived(countMatches(nodes, searchQuery));

	// Reset highlight when search changes
	$effect(() => {
		searchQuery; // dependency
		highlightedIndex = -1;
	});

	// Get currently highlighted node
	const highlightedNode = $derived(
		highlightedIndex >= 0 && highlightedIndex < flattenedNodes.length
			? flattenedNodes[highlightedIndex]
			: null
	);

	// Scroll highlighted item into view
	function scrollToHighlighted() {
		setTimeout(() => {
			const highlighted = document.querySelector(`[data-tree-type="${treeType}"] .tree-node.keyboard-highlight`);
			if (highlighted) {
				highlighted.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
			}
		}, 0);
	}

	// Handle keyboard navigation
	function handleKeydown(e: KeyboardEvent) {
		// Don't handle if typing in search
		if (e.target === searchInputRef) {
			if (e.key === 'Escape') {
				clearSearchQuery();
				searchInputRef.blur();
				return;
			}
			if (e.key === 'ArrowDown') {
				e.preventDefault();
				// Move focus to tree
				if (flattenedNodes.length > 0) {
					highlightedIndex = 0;
					scrollToHighlighted();
				}
				return;
			}
			return;
		}

		if (e.key === 'ArrowDown') {
			e.preventDefault();
			if (flattenedNodes.length > 0) {
				highlightedIndex = Math.min(highlightedIndex + 1, flattenedNodes.length - 1);
				scrollToHighlighted();
			}
			return;
		}

		if (e.key === 'ArrowUp') {
			e.preventDefault();
			if (flattenedNodes.length > 0) {
				if (highlightedIndex <= 0) {
					// Move focus back to search
					highlightedIndex = -1;
					searchInputRef?.focus();
				} else {
					highlightedIndex = highlightedIndex - 1;
					scrollToHighlighted();
				}
			}
			return;
		}

		if (e.key === 'ArrowRight' && highlightedNode) {
			e.preventDefault();
			if (highlightedNode.children && !isNodeExpanded(highlightedNode.id)) {
				toggleNodeExpansion(highlightedNode.id);
			}
			return;
		}

		if (e.key === 'ArrowLeft' && highlightedNode) {
			e.preventDefault();
			if (highlightedNode.children && isNodeExpanded(highlightedNode.id)) {
				toggleNodeExpansion(highlightedNode.id);
			} else {
				// Go to parent
				const currentIndex = highlightedIndex;
				const currentNode = flattenedNodes[currentIndex];
				// Find parent by looking for the node at a lower depth that contains this one
				for (let i = currentIndex - 1; i >= 0; i--) {
					const potentialParent = flattenedNodes[i];
					if (potentialParent.children?.some(c => c.id === currentNode.id)) {
						highlightedIndex = i;
						scrollToHighlighted();
						break;
					}
				}
			}
			return;
		}

		if (e.key === 'Enter' && highlightedNode) {
			e.preventDefault();
			// Select the node
			onSelect?.(highlightedNode);
			// Open mapping modal
			onAddClick?.(highlightedNode, treeType);
			return;
		}

		if (e.key === '/' || (e.key === 'f' && (e.ctrlKey || e.metaKey))) {
			e.preventDefault();
			searchInputRef?.focus();
			return;
		}

		if (e.key === 'Escape') {
			highlightedIndex = -1;
			return;
		}

		// Home/End navigation
		if (e.key === 'Home') {
			e.preventDefault();
			if (flattenedNodes.length > 0) {
				highlightedIndex = 0;
				scrollToHighlighted();
			}
			return;
		}

		if (e.key === 'End') {
			e.preventDefault();
			if (flattenedNodes.length > 0) {
				highlightedIndex = flattenedNodes.length - 1;
				scrollToHighlighted();
			}
			return;
		}
	}

	// Handle node click from TreeNodeComponent
	function handleNodeSelect(node: TreeNode) {
		onSelect?.(node);
		// Update highlight to match selection
		const idx = flattenedNodes.findIndex(n => n.id === node.id);
		if (idx >= 0) highlightedIndex = idx;
	}

	// Handle focus
	function handleFocus() {
		hasFocus = true;
	}

	function handleBlur(e: FocusEvent) {
		// Only blur if focus is leaving the tree container entirely
		const container = e.currentTarget as HTMLElement;
		const relatedTarget = e.relatedTarget as HTMLElement;
		if (!container.contains(relatedTarget)) {
			hasFocus = false;
		}
	}
</script>

<div
	class="source-tree-container"
	class:has-focus={hasFocus}
	onkeydown={handleKeydown}
	onfocusin={handleFocus}
	onfocusout={handleBlur}
	role="tree"
	aria-label="{treeType === 'source' ? 'Source' : 'Target'} tree"
>
	<!-- Always visible search -->
	<div class="search-container">
		<div class="search-input-wrapper">
			<svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
				<circle cx="11" cy="11" r="8" />
				<path d="M21 21l-4.35-4.35" />
			</svg>
			<input
				type="text"
				class="search-input"
				placeholder="Search fields... (/ to focus)"
				bind:value={searchQuery}
				bind:this={searchInputRef}
			/>
			{#if searchQuery}
				<span class="match-count">{matchCount}</span>
				<button class="clear-btn" onclick={clearSearchQuery} aria-label="Clear search">
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
						<path d="M18 6L6 18M6 6l12 12" />
					</svg>
				</button>
			{/if}
		</div>
	</div>

	<!-- Keyboard hints (shown when focused) -->
	{#if hasFocus && highlightedIndex >= 0}
		<div class="keyboard-hint">
			<kbd>↑↓</kbd> navigate
			<kbd>←→</kbd> collapse/expand
			<kbd>Enter</kbd> map
		</div>
	{/if}

	<div class="source-tree" data-tree-type={treeType}>
		{#each filteredNodes as node (node.id)}
			<TreeNodeComponent
				{node}
				selectedId={selectedId}
				{treeType}
				onSelect={handleNodeSelect}
				{onMappingClick}
				{onAddClick}
				searchQuery={searchQuery}
				forceExpand={!!searchQuery}
				highlightedId={highlightedNode?.id}
				expandedNodes={expandedNodes}
				onToggleExpand={toggleNodeExpansion}
			/>
		{/each}
		{#if searchQuery && filteredNodes.length === 0}
			<div class="no-results">No fields match "{searchQuery}"</div>
		{/if}
	</div>
</div>

<style>
	.source-tree-container {
		display: flex;
		flex-direction: column;
		height: 100%;
		outline: none;
	}

	.source-tree-container.has-focus {
		/* Visual indicator that tree has keyboard focus */
	}

	.search-container {
		padding: 8px;
		border-bottom: 1px solid #374151;
		background: #1f2937;
	}

	.search-input-wrapper {
		position: relative;
		display: flex;
		align-items: center;
	}

	.search-icon {
		position: absolute;
		left: 10px;
		width: 14px;
		height: 14px;
		color: #6b7280;
		pointer-events: none;
	}

	.search-input {
		width: 100%;
		padding: 8px 70px 8px 32px;
		font-size: 12px;
		border: 1px solid #374151;
		border-radius: 6px;
		background: #111827;
		color: #e5e7eb;
		outline: none;
		transition: border-color 0.15s, box-shadow 0.15s;
	}

	.search-input:focus {
		border-color: #3b82f6;
		box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.2);
	}

	.search-input::placeholder {
		color: #6b7280;
	}

	.match-count {
		position: absolute;
		right: 32px;
		font-size: 10px;
		color: #9ca3af;
		background: #374151;
		padding: 2px 6px;
		border-radius: 8px;
	}

	.clear-btn {
		position: absolute;
		right: 6px;
		width: 22px;
		height: 22px;
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 0;
		border: none;
		background: transparent;
		color: #6b7280;
		cursor: pointer;
		border-radius: 4px;
		transition: all 0.15s;
	}

	.clear-btn:hover {
		color: #e5e7eb;
		background: #374151;
	}

	.clear-btn svg {
		width: 14px;
		height: 14px;
	}

	.keyboard-hint {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 4px 8px;
		font-size: 10px;
		color: #6b7280;
		background: #111827;
		border-bottom: 1px solid #374151;
	}

	.keyboard-hint kbd {
		padding: 1px 4px;
		font-family: ui-monospace, monospace;
		font-size: 9px;
		background: #374151;
		border-radius: 3px;
		color: #d1d5db;
	}

	.source-tree {
		flex: 1;
		overflow: auto;
		font-family: ui-monospace, 'SF Mono', 'Cascadia Code', 'Segoe UI Mono', monospace;
		font-size: 13px;
		padding: 4px;
	}

	.no-results {
		padding: 16px;
		text-align: center;
		color: #6b7280;
		font-size: 12px;
	}
</style>
