<script lang="ts">
	import type { TreeNode } from '$lib/types';
	import { sampleSourceTree, sampleTargetTree } from '$lib/sampleData';
	import MappingHelpPanel from './MappingHelpPanel.svelte';

	interface Props {
		isOpen: boolean;
		sourceNode: TreeNode | null;
		sourceTreeType: 'source' | 'target';
		onClose: () => void;
		onCreateMapping: (sourceNode: TreeNode, targetNode: TreeNode) => void;
	}

	let { isOpen, sourceNode, sourceTreeType, onClose, onCreateMapping }: Props = $props();

	// The tree we're selecting from (opposite of sourceTreeType)
	const targetTreeType = $derived(sourceTreeType === 'source' ? 'target' : 'source');
	const targetTree = $derived(sourceTreeType === 'source' ? sampleTargetTree : sampleSourceTree);

	// Search state
	let searchQuery = $state('');
	let searchInputRef: HTMLInputElement;

	// Selected node in the modal
	let selectedNode = $state<TreeNode | null>(null);

	// Keyboard navigation - highlighted index in flattened list
	let highlightedIndex = $state(-1);

	// Expanded nodes in the modal tree (node ids)
	let expandedNodes = $state<Set<string>>(new Set());

	// Tab state: 'docs', 'stats', or 'help'
	let activeTab = $state<'docs' | 'stats' | 'help'>('docs');

	// Stats loading state
	let statsLoaded = $state(false);
	let statsLoading = $state(false);

	// Reset state when modal opens
	$effect(() => {
		if (isOpen) {
			searchQuery = '';
			selectedNode = null;
			highlightedIndex = -1;
			activeTab = 'docs';
			statsLoaded = false;
			statsLoading = false;
			// Initialize with first two levels expanded
			const initialExpanded = new Set<string>();
			for (const node of targetTree) {
				initialExpanded.add(node.id);
				if (node.children) {
					for (const child of node.children) {
						initialExpanded.add(child.id);
					}
				}
			}
			expandedNodes = initialExpanded;

			// Focus search input after a tick
			setTimeout(() => {
				searchInputRef?.focus();
			}, 50);
		}
	});

	// Toggle expand/collapse for a node
	function toggleNode(nodeId: string, e: MouseEvent) {
		e.stopPropagation();
		const newExpanded = new Set(expandedNodes);
		if (newExpanded.has(nodeId)) {
			newExpanded.delete(nodeId);
		} else {
			newExpanded.add(nodeId);
		}
		expandedNodes = newExpanded;
	}

	// Check if node is expanded
	function isNodeExpanded(nodeId: string): boolean {
		// Force expand all when searching
		if (searchQuery.trim()) return true;
		return expandedNodes.has(nodeId);
	}

	// Load stats on demand
	function loadStats() {
		if (statsLoaded || statsLoading) return;
		statsLoading = true;
		// Simulate loading stats (in real app, this would be an API call)
		setTimeout(() => {
			statsLoaded = true;
			statsLoading = false;
		}, 500);
	}

	// Switch to stats tab and load if needed
	function switchToStats() {
		activeTab = 'stats';
		loadStats();
	}

	// Filter tree nodes based on search
	function nodeMatchesSearch(node: TreeNode, query: string): boolean {
		if (!query.trim()) return true;
		const lowerQuery = query.toLowerCase();
		if (node.name.toLowerCase().includes(lowerQuery)) return true;
		if (node.children) {
			return node.children.some((child) => nodeMatchesSearch(child, lowerQuery));
		}
		return false;
	}

	function filterNodes(nodes: TreeNode[], query: string): TreeNode[] {
		if (!query.trim()) return nodes;
		return nodes
			.filter((node) => nodeMatchesSearch(node, query))
			.map((node) => {
				if (node.children) {
					return { ...node, children: filterNodes(node.children, query) };
				}
				return node;
			});
	}

	const filteredTree = $derived(filterNodes(targetTree, searchQuery));

	// Flatten tree for keyboard navigation (only visible/expanded nodes)
	function flattenTree(nodes: TreeNode[]): TreeNode[] {
		const result: TreeNode[] = [];
		for (const node of nodes) {
			result.push(node);
			if (node.children && isNodeExpanded(node.id)) {
				result.push(...flattenTree(node.children));
			}
		}
		return result;
	}

	const flattenedNodes = $derived(flattenTree(filteredTree));

	// Reset highlight when search changes
	$effect(() => {
		// Access searchQuery to create dependency
		searchQuery;
		highlightedIndex = -1;
	});

	// Handle node selection
	function handleNodeSelect(node: TreeNode) {
		selectedNode = node;
		// Update highlighted index to match selection
		const idx = flattenedNodes.findIndex(n => n.id === node.id);
		if (idx >= 0) highlightedIndex = idx;
	}

	// Handle create mapping
	function handleCreateMapping() {
		if (sourceNode && selectedNode) {
			onCreateMapping(sourceNode, selectedNode);
			onClose();
		}
	}

	// Handle backdrop click
	function handleBackdropClick(e: MouseEvent) {
		if (e.target === e.currentTarget) {
			onClose();
		}
	}

	// Handle keyboard navigation
	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			onClose();
			return;
		}

		if (e.key === 'ArrowDown') {
			e.preventDefault();
			if (flattenedNodes.length > 0) {
				highlightedIndex = Math.min(highlightedIndex + 1, flattenedNodes.length - 1);
				selectedNode = flattenedNodes[highlightedIndex];
				scrollToHighlighted();
			}
			return;
		}

		if (e.key === 'ArrowUp') {
			e.preventDefault();
			if (flattenedNodes.length > 0) {
				highlightedIndex = Math.max(highlightedIndex - 1, 0);
				selectedNode = flattenedNodes[highlightedIndex];
				scrollToHighlighted();
			}
			return;
		}

		if (e.key === 'Enter') {
			e.preventDefault();
			if (selectedNode) {
				// If we have a selected node, create the mapping
				handleCreateMapping();
			} else if (highlightedIndex >= 0 && flattenedNodes[highlightedIndex]) {
				// Select the highlighted node
				selectedNode = flattenedNodes[highlightedIndex];
			} else if (flattenedNodes.length === 1) {
				// If only one result, select and create
				selectedNode = flattenedNodes[0];
				handleCreateMapping();
			}
			return;
		}
	}

	// Scroll the highlighted item into view
	function scrollToHighlighted() {
		setTimeout(() => {
			const highlighted = document.querySelector('.tree-node.highlighted');
			if (highlighted) {
				highlighted.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
			}
		}, 0);
	}
</script>

<svelte:window onkeydown={handleKeydown} />

{#if isOpen && sourceNode}
	<div
		class="modal-backdrop"
		onclick={handleBackdropClick}
		onkeydown={(e) => { if (e.key === 'Escape') onClose(); }}
		role="dialog"
		aria-modal="true"
		tabindex="-1"
	>
		<div class="modal">
			<!-- Header -->
			<div class="modal-header">
				<h2 class="modal-title">
					{#if sourceTreeType === 'source'}
						Map <span class="field-name">{sourceNode.name}</span> to...
					{:else}
						Map to <span class="field-name">{sourceNode.name}</span> from...
					{/if}
				</h2>
				<button class="close-btn" onclick={onClose} aria-label="Close">
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
						<path d="M18 6L6 18M6 6l12 12" />
					</svg>
				</button>
			</div>

			<!-- Search -->
			<div class="modal-search">
				<svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
					<circle cx="11" cy="11" r="8" />
					<path d="M21 21l-4.35-4.35" />
				</svg>
				<input
					type="text"
					class="search-input"
					placeholder="Search {targetTreeType} fields..."
					bind:value={searchQuery}
					bind:this={searchInputRef}
				/>
				{#if searchQuery}
					<button class="clear-btn" onclick={() => searchQuery = ''} aria-label="Clear search">
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
							<path d="M18 6L6 18M6 6l12 12" />
						</svg>
					</button>
				{/if}
			</div>

			<!-- Content -->
			<div class="modal-content">
				<!-- Tree panel -->
				<div class="tree-panel">
					<div class="panel-header">
						<span class="panel-title">{targetTreeType === 'target' ? 'Target Schema' : 'Source Structure'}</span>
					</div>
					<div class="tree-content">
						{#each filteredTree as node (node.id)}
							{@render treeNode(node, 0)}
						{/each}
						{#if filteredTree.length === 0}
							<div class="no-results">No fields match "{searchQuery}"</div>
						{/if}
					</div>
				</div>

				<!-- Details panel -->
				<div class="details-panel">
					<div class="tabs">
						<button
							class="tab"
							class:active={activeTab === 'docs'}
							onclick={() => activeTab = 'docs'}
						>
							Documentation
						</button>
						<button
							class="tab"
							class:active={activeTab === 'stats'}
							onclick={switchToStats}
						>
							Statistics
						</button>
						<button
							class="tab"
							class:active={activeTab === 'help'}
							onclick={() => activeTab = 'help'}
						>
							<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="tab-icon">
								<circle cx="12" cy="12" r="10" />
								<path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3M12 17h.01" />
							</svg>
							Help
						</button>
					</div>

					<div class="tab-content">
						{#if activeTab === 'help'}
							<MappingHelpPanel
								fieldType={selectedNode?.isAttribute ? 'attribute' : 'text'}
								compact={true}
							/>
						{:else if !selectedNode}
							<div class="placeholder">
								Select a field to view details
							</div>
						{:else if activeTab === 'docs'}
							<div class="docs-content">
								<h3 class="field-title">{selectedNode.name}</h3>
								<dl class="field-info">
									<dt>Type</dt>
									<dd>{selectedNode.isAttribute ? 'Attribute' : 'Element'}</dd>
									<dt>Path</dt>
									<dd class="path">{selectedNode.path}</dd>
									{#if selectedNode.count !== undefined}
										<dt>Occurrences</dt>
										<dd>{selectedNode.count.toLocaleString()}</dd>
									{/if}
								</dl>
								<div class="doc-description">
									<p>
										{#if targetTreeType === 'target'}
											This field is part of the EDM target schema.
										{:else}
											This field comes from the source XML structure.
										{/if}
									</p>
								</div>
							</div>
						{:else}
							<div class="stats-content">
								{#if statsLoading}
									<div class="loading">
										<span class="spinner"></span>
										Loading statistics...
									</div>
								{:else if statsLoaded}
									<h3 class="field-title">{selectedNode.name}</h3>
									<div class="stats-grid">
										<div class="stat-item">
											<span class="stat-label">Total Values</span>
											<span class="stat-value">{selectedNode.count?.toLocaleString() ?? 'N/A'}</span>
										</div>
										<div class="stat-item">
											<span class="stat-label">Unique Values</span>
											<span class="stat-value">{Math.floor((selectedNode.count ?? 100) * 0.7).toLocaleString()}</span>
										</div>
										<div class="stat-item">
											<span class="stat-label">Empty</span>
											<span class="stat-value">0%</span>
										</div>
										<div class="stat-item">
											<span class="stat-label">Avg Length</span>
											<span class="stat-value">24 chars</span>
										</div>
									</div>
									<div class="sample-values">
										<h4>Sample Values</h4>
										<ul>
											<li>"Example value 1"</li>
											<li>"Example value 2"</li>
											<li>"Example value 3"</li>
										</ul>
									</div>
								{/if}
							</div>
						{/if}
					</div>
				</div>
			</div>

			<!-- Footer -->
			<div class="modal-footer">
				<div class="keyboard-hint">
					<kbd>↑</kbd><kbd>↓</kbd> navigate
					<kbd>Enter</kbd> {selectedNode ? 'create mapping' : 'select'}
					<kbd>Esc</kbd> close
				</div>
				<div class="footer-actions">
					<button class="btn btn-secondary" onclick={onClose}>Cancel</button>
					<button
						class="btn btn-primary"
						onclick={handleCreateMapping}
						disabled={!selectedNode}
					>
						Create Mapping
					</button>
				</div>
			</div>
		</div>
	</div>
{/if}

<!-- Recursive tree node component -->
{#snippet treeNode(node: TreeNode, depth: number)}
	{@const hasChildren = node.children && node.children.length > 0}
	{@const nodeExpanded = isNodeExpanded(node.id)}
	{@const nodeIndex = flattenedNodes.findIndex(n => n.id === node.id)}
	{@const isHighlighted = nodeIndex === highlightedIndex}
	<div class="tree-node-wrapper">
		<button
			class="tree-node"
			class:selected={selectedNode?.id === node.id}
			class:highlighted={isHighlighted}
			class:has-children={hasChildren}
			style="padding-left: {depth * 16 + 8}px"
			onclick={() => handleNodeSelect(node)}
		>
			{#if hasChildren}
				<span
					class="toggle"
					onclick={(e) => toggleNode(node.id, e)}
					onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') toggleNode(node.id, e as unknown as MouseEvent); }}
					role="button"
					tabindex="-1"
				>
					<svg class="toggle-icon" class:expanded={nodeExpanded} viewBox="0 0 20 20" fill="currentColor">
						<path fill-rule="evenodd" d="M7.21 14.77a.75.75 0 01.02-1.06L11.168 10 7.23 6.29a.75.75 0 111.04-1.08l4.5 4.25a.75.75 0 010 1.08l-4.5 4.25a.75.75 0 01-1.06-.02z" clip-rule="evenodd" />
					</svg>
				</span>
			{:else}
				<span class="toggle-placeholder"></span>
			{/if}
			<span class="node-icon" class:attribute={node.isAttribute}>
				{node.isAttribute ? '@' : '<>'}
			</span>
			<span class="node-name">
				{#if searchQuery}
					{@const lowerName = node.name.toLowerCase()}
					{@const lowerQuery = searchQuery.toLowerCase()}
					{@const idx = lowerName.indexOf(lowerQuery)}
					{#if idx >= 0}
						{node.name.slice(0, idx)}<mark>{node.name.slice(idx, idx + searchQuery.length)}</mark>{node.name.slice(idx + searchQuery.length)}
					{:else}
						{node.name}
					{/if}
				{:else}
					{node.name}
				{/if}
			</span>
			{#if node.count !== undefined}
				<span class="count">{node.count.toLocaleString()}</span>
			{/if}
		</button>
		{#if hasChildren && nodeExpanded}
			<div class="children">
				{#each node.children as child (child.id)}
					{@render treeNode(child, depth + 1)}
				{/each}
			</div>
		{/if}
	</div>
{/snippet}

<style>
	.modal-backdrop {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.7);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 1000;
		padding: 24px;
	}

	.modal {
		background: #1f2937;
		border-radius: 12px;
		width: 100%;
		max-width: 900px;
		height: 70vh;
		display: flex;
		flex-direction: column;
		box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5);
		border: 1px solid #374151;
	}

	.modal-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 16px 20px;
		border-bottom: 1px solid #374151;
	}

	.modal-title {
		font-size: 16px;
		font-weight: 600;
		color: #f3f4f6;
		margin: 0;
	}

	.field-name {
		color: #60a5fa;
		font-family: ui-monospace, monospace;
	}

	.close-btn {
		width: 32px;
		height: 32px;
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 0;
		border: none;
		background: transparent;
		color: #9ca3af;
		border-radius: 6px;
		cursor: pointer;
	}

	.close-btn:hover {
		background: #374151;
		color: #f3f4f6;
	}

	.close-btn svg {
		width: 20px;
		height: 20px;
	}

	.modal-search {
		position: relative;
		padding: 12px 20px;
		border-bottom: 1px solid #374151;
	}

	.search-icon {
		position: absolute;
		left: 32px;
		top: 50%;
		transform: translateY(-50%);
		width: 16px;
		height: 16px;
		color: #6b7280;
	}

	.search-input {
		width: 100%;
		padding: 10px 36px;
		font-size: 14px;
		border: 1px solid #374151;
		border-radius: 8px;
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
		right: 28px;
		top: 50%;
		transform: translateY(-50%);
		width: 24px;
		height: 24px;
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 0;
		border: none;
		background: transparent;
		color: #6b7280;
		cursor: pointer;
		border-radius: 4px;
	}

	.clear-btn:hover {
		color: #9ca3af;
		background: #374151;
	}

	.clear-btn svg {
		width: 14px;
		height: 14px;
	}

	.modal-content {
		display: flex;
		flex: 1;
		min-height: 0;
		overflow: hidden;
	}

	.tree-panel {
		width: 50%;
		display: flex;
		flex-direction: column;
		border-right: 1px solid #374151;
		min-height: 0;
	}

	.panel-header {
		padding: 8px 12px;
		background: #111827;
		border-bottom: 1px solid #374151;
	}

	.panel-title {
		font-size: 11px;
		font-weight: 500;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: #9ca3af;
	}

	.tree-content {
		flex: 1;
		overflow: auto;
		padding: 8px;
		min-height: 0;
	}

	.no-results {
		padding: 24px;
		text-align: center;
		color: #6b7280;
		font-size: 13px;
	}

	/* Tree node styles */
	.tree-node-wrapper {
		font-family: ui-monospace, monospace;
		font-size: 13px;
	}

	.tree-node {
		display: flex;
		align-items: center;
		gap: 4px;
		width: 100%;
		padding: 6px 8px;
		border: none;
		background: transparent;
		color: #e5e7eb;
		text-align: left;
		cursor: pointer;
		border-radius: 4px;
		transition: background 0.1s;
	}

	.tree-node:hover {
		background: #374151;
	}

	.tree-node.highlighted {
		background: #374151;
		outline: 1px solid #60a5fa;
		outline-offset: -1px;
	}

	.tree-node.selected {
		background: #1e3a5f;
	}

	.tree-node.selected.highlighted {
		outline: 1px solid #93c5fd;
		outline-offset: -1px;
	}

	.toggle {
		width: 16px;
		height: 16px;
		display: flex;
		align-items: center;
		justify-content: center;
		color: #6b7280;
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
	}

	.node-icon {
		font-size: 10px;
		font-weight: 600;
		padding: 1px 3px;
		border-radius: 2px;
		color: #60a5fa;
		background: rgba(96, 165, 250, 0.1);
	}

	.node-icon.attribute {
		color: #f472b6;
		background: rgba(244, 114, 182, 0.1);
	}

	.node-name {
		flex: 1;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.node-name mark {
		background: rgba(250, 204, 21, 0.4);
		color: inherit;
		padding: 0 1px;
		border-radius: 2px;
	}

	.count {
		font-size: 10px;
		color: #6b7280;
		background: #374151;
		padding: 1px 6px;
		border-radius: 10px;
	}

	.children {
		/* Children are always expanded in modal for simplicity */
	}

	/* Details panel */
	.details-panel {
		width: 50%;
		display: flex;
		flex-direction: column;
		min-height: 0;
	}

	.tabs {
		display: flex;
		border-bottom: 1px solid #374151;
		background: #111827;
	}

	.tab {
		flex: 1;
		padding: 10px 16px;
		border: none;
		background: transparent;
		color: #9ca3af;
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
		transition: all 0.15s;
		border-bottom: 2px solid transparent;
	}

	.tab:hover {
		color: #e5e7eb;
	}

	.tab.active {
		color: #3b82f6;
		border-bottom-color: #3b82f6;
	}

	.tab-icon {
		width: 14px;
		height: 14px;
		vertical-align: -2px;
		margin-right: 4px;
	}

	.tab-content {
		flex: 1;
		overflow: auto;
		padding: 16px;
		min-height: 0;
	}

	.placeholder {
		display: flex;
		align-items: center;
		justify-content: center;
		height: 100%;
		color: #6b7280;
		font-size: 13px;
	}

	.docs-content, .stats-content {
		color: #e5e7eb;
	}

	.field-title {
		font-size: 16px;
		font-weight: 600;
		margin: 0 0 16px 0;
		color: #f3f4f6;
		font-family: ui-monospace, monospace;
	}

	.field-info {
		display: grid;
		grid-template-columns: auto 1fr;
		gap: 8px 16px;
		margin: 0 0 16px 0;
		font-size: 13px;
	}

	.field-info dt {
		color: #9ca3af;
	}

	.field-info dd {
		margin: 0;
		color: #e5e7eb;
	}

	.field-info .path {
		font-family: ui-monospace, monospace;
		font-size: 12px;
		color: #6b7280;
		word-break: break-all;
	}

	.doc-description {
		font-size: 13px;
		color: #9ca3af;
		line-height: 1.5;
	}

	.doc-description p {
		margin: 0;
	}

	.loading {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 12px;
		height: 100%;
		color: #9ca3af;
		font-size: 13px;
	}

	.spinner {
		width: 20px;
		height: 20px;
		border: 2px solid #374151;
		border-top-color: #3b82f6;
		border-radius: 50%;
		animation: spin 0.8s linear infinite;
	}

	@keyframes spin {
		to { transform: rotate(360deg); }
	}

	.stats-grid {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 12px;
		margin-bottom: 20px;
	}

	.stat-item {
		background: #111827;
		padding: 12px;
		border-radius: 8px;
	}

	.stat-label {
		display: block;
		font-size: 11px;
		color: #6b7280;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		margin-bottom: 4px;
	}

	.stat-value {
		font-size: 18px;
		font-weight: 600;
		color: #f3f4f6;
	}

	.sample-values h4 {
		font-size: 12px;
		font-weight: 500;
		color: #9ca3af;
		margin: 0 0 8px 0;
		text-transform: uppercase;
		letter-spacing: 0.05em;
	}

	.sample-values ul {
		margin: 0;
		padding: 0;
		list-style: none;
	}

	.sample-values li {
		padding: 6px 10px;
		font-family: ui-monospace, monospace;
		font-size: 12px;
		color: #e5e7eb;
		background: #111827;
		border-radius: 4px;
		margin-bottom: 4px;
	}

	/* Footer */
	.modal-footer {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 16px 20px;
		border-top: 1px solid #374151;
	}

	.keyboard-hint {
		display: flex;
		align-items: center;
		gap: 6px;
		font-size: 11px;
		color: #6b7280;
	}

	.keyboard-hint kbd {
		display: inline-block;
		padding: 2px 6px;
		font-family: ui-monospace, monospace;
		font-size: 10px;
		background: #374151;
		border-radius: 4px;
		color: #d1d5db;
	}

	.footer-actions {
		display: flex;
		gap: 12px;
	}

	.btn {
		padding: 10px 20px;
		font-size: 14px;
		font-weight: 500;
		border: none;
		border-radius: 8px;
		cursor: pointer;
		transition: all 0.15s;
	}

	.btn:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	.btn-secondary {
		background: #374151;
		color: #e5e7eb;
	}

	.btn-secondary:hover:not(:disabled) {
		background: #4b5563;
	}

	.btn-primary {
		background: #3b82f6;
		color: white;
	}

	.btn-primary:hover:not(:disabled) {
		background: #2563eb;
	}
</style>
