<script lang="ts">
	import type { TreeNode } from '$lib/types';
	import TreeNodeComponent from './TreeNode.svelte';

	interface Props {
		nodes: TreeNode[];
		selectedId?: string | null;
		treeType: 'source' | 'target';
		onSelect?: (node: TreeNode) => void;
		onMappingClick?: (mappingId: string) => void;
		showSearchToggle?: boolean;
	}

	let { nodes, selectedId = null, treeType, onSelect, onMappingClick, showSearchToggle = true }: Props = $props();

	// Search state
	let searchQuery = $state('');
	let showSearch = $state(false);

	// Toggle search visibility
	function toggleSearch() {
		showSearch = !showSearch;
		if (!showSearch) {
			searchQuery = '';
		}
	}

	// Clear search
	function clearSearchQuery() {
		searchQuery = '';
	}

	// Check if a node or any of its descendants match the search
	function nodeMatchesSearch(node: TreeNode, query: string): boolean {
		const lowerQuery = query.toLowerCase();
		// Check if this node matches
		if (node.name.toLowerCase().includes(lowerQuery)) {
			return true;
		}
		// Check if any child matches
		if (node.children) {
			return node.children.some((child) => nodeMatchesSearch(child, lowerQuery));
		}
		return false;
	}

	// Filter nodes to only include those matching search (keeping parent hierarchy)
	function filterNodes(nodeList: TreeNode[], query: string): TreeNode[] {
		if (!query.trim()) return nodeList;

		const lowerQuery = query.toLowerCase();
		return nodeList
			.filter((node) => nodeMatchesSearch(node, lowerQuery))
			.map((node) => {
				// If node has children, filter them recursively
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

</script>

<div class="source-tree-container">
	{#if showSearchToggle}
		<div class="tree-header">
			<button
				class="search-toggle"
				class:active={showSearch}
				onclick={toggleSearch}
				aria-label={showSearch ? 'Hide search' : 'Search fields'}
				title="Search fields"
			>
				<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
					<circle cx="11" cy="11" r="8" />
					<path d="M21 21l-4.35-4.35" />
				</svg>
			</button>
			{#if searchQuery}
				<span class="search-info">{matchCount} {matchCount === 1 ? 'match' : 'matches'}</span>
			{/if}
		</div>
	{/if}

	{#if showSearch}
		<div class="search-container">
			<div class="search-input-wrapper">
				<svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
					<circle cx="11" cy="11" r="8" />
					<path d="M21 21l-4.35-4.35" />
				</svg>
				<input
					type="text"
					class="search-input"
					placeholder="Search fields..."
					bind:value={searchQuery}
				/>
				{#if searchQuery}
					<button class="clear-btn" onclick={clearSearchQuery} aria-label="Clear search">
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
							<path d="M18 6L6 18M6 6l12 12" />
						</svg>
					</button>
				{/if}
			</div>
		</div>
	{/if}

	<div class="source-tree" data-tree-type={treeType}>
		{#each filteredNodes as node (node.id)}
			<TreeNodeComponent
				{node}
				{selectedId}
				{treeType}
				{onSelect}
				{onMappingClick}
				searchQuery={searchQuery}
				forceExpand={!!searchQuery}
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
	}

	.tree-header {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 6px 8px;
		background: #1f2937;
		border-bottom: 1px solid #374151;
	}

	.search-toggle {
		width: 24px;
		height: 24px;
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 0;
		border: none;
		background: transparent;
		color: #6b7280;
		border-radius: 4px;
		cursor: pointer;
		transition: all 0.15s;
	}

	.search-toggle:hover {
		color: #9ca3af;
		background: #374151;
	}

	.search-toggle.active {
		color: #3b82f6;
		background: rgba(59, 130, 246, 0.1);
	}

	.search-toggle svg {
		width: 14px;
		height: 14px;
	}

	.search-info {
		font-size: 11px;
		color: #6b7280;
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
		left: 8px;
		width: 14px;
		height: 14px;
		color: #6b7280;
		pointer-events: none;
	}

	.search-input {
		width: 100%;
		padding: 6px 28px 6px 28px;
		font-size: 12px;
		border: 1px solid #374151;
		border-radius: 4px;
		background: #111827;
		color: #e5e7eb;
		outline: none;
	}

	.search-input:focus {
		border-color: #3b82f6;
	}

	.search-input::placeholder {
		color: #6b7280;
	}

	.clear-btn {
		position: absolute;
		right: 4px;
		width: 20px;
		height: 20px;
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 0;
		border: none;
		background: transparent;
		color: #6b7280;
		cursor: pointer;
		border-radius: 2px;
	}

	.clear-btn:hover {
		color: #9ca3af;
		background: #374151;
	}

	.clear-btn svg {
		width: 12px;
		height: 12px;
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
