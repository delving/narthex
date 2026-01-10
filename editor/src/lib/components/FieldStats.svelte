<script lang="ts">
	import type { TreeNode } from '$lib/types';

	interface Props {
		node: TreeNode | null;
		type: 'source' | 'target';
	}

	let { node, type }: Props = $props();

	// Tab state
	let activeTab = $state<'stats' | 'docs'>('stats');

	// Automatically switch to docs tab when target node with documentation is selected
	$effect(() => {
		if (type === 'target' && node?.documentation) {
			activeTab = 'docs';
		} else if (type === 'source') {
			activeTab = 'stats';
		}
	});

	// Sample histogram data - would come from API
	const sampleHistogram = [
		{ value: 'Rembrandt van Rijn', count: 342 },
		{ value: 'Vincent van Gogh', count: 287 },
		{ value: 'Johannes Vermeer', count: 156 },
		{ value: 'Frans Hals', count: 134 },
		{ value: 'Jan Steen', count: 98 },
		{ value: 'Pieter de Hooch', count: 87 },
		{ value: 'Jacob van Ruisdael', count: 76 },
		{ value: 'Gerard ter Borch', count: 65 },
		{ value: 'Aelbert Cuyp', count: 54 },
		{ value: 'Paulus Potter', count: 43 }
	];

	const maxCount = $derived(Math.max(...sampleHistogram.map((h) => h.count)));
	const hasDocumentation = $derived(type === 'target' && node?.documentation);
</script>

<div class="field-stats">
	{#if node}
		<div class="stats-header">
			<div class="stats-title">
				<span class="stats-type">{type === 'source' ? 'Source' : 'Target'}</span>
				<span class="stats-name">{node.name}</span>
			</div>
			<div class="stats-path">{node.path}</div>
		</div>

		<!-- Tabs -->
		<div class="tabs">
			<button
				class="tab"
				class:active={activeTab === 'stats'}
				onclick={() => (activeTab = 'stats')}
			>
				Stats
			</button>
			<button
				class="tab"
				class:active={activeTab === 'docs'}
				class:disabled={!hasDocumentation}
				onclick={() => hasDocumentation && (activeTab = 'docs')}
				disabled={!hasDocumentation}
			>
				Documentation
			</button>
		</div>

		<!-- Stats Tab Content -->
		{#if activeTab === 'stats'}
			<div class="tab-content">
				<div class="stats-summary">
					<div class="stat-item">
						<span class="stat-label">Occurrences</span>
						<span class="stat-value">{node.count?.toLocaleString() ?? 'N/A'}</span>
					</div>
					<div class="stat-item">
						<span class="stat-label">Unique Values</span>
						<span class="stat-value">{sampleHistogram.length.toLocaleString()}</span>
					</div>
					<div class="stat-item">
						<span class="stat-label">Status</span>
						<span class="stat-value" class:mapped={node.mappedTo?.length || node.mappedFrom?.length}>
							{node.mappedTo?.length || node.mappedFrom?.length ? 'Mapped' : 'Unmapped'}
						</span>
					</div>
				</div>

				<div class="histogram-section">
					<div class="histogram-header">Top Values</div>
					<div class="histogram">
						{#each sampleHistogram as item}
							<div class="histogram-row">
								<div class="histogram-value" title={item.value}>{item.value}</div>
								<div class="histogram-bar-container">
									<div
										class="histogram-bar"
										style="width: {(item.count / maxCount) * 100}%"
									></div>
								</div>
								<div class="histogram-count">{item.count}</div>
							</div>
						{/each}
					</div>
				</div>

				{#if node.mappedTo?.length || node.mappedFrom?.length}
					<div class="mapping-info">
						<div class="mapping-header">Mapping</div>
						<div class="mapping-code">
							<code>output.{node.name} = input.{node.name}?.text()</code>
						</div>
					</div>
				{/if}
			</div>

		<!-- Documentation Tab Content -->
		{:else if activeTab === 'docs' && node.documentation}
			<div class="tab-content docs-content">
				{#if node.documentation.description}
					<div class="doc-section">
						<div class="doc-label">Description</div>
						<div class="doc-value">{node.documentation.description}</div>
					</div>
				{/if}

				<div class="doc-meta">
					{#if node.documentation.dataType}
						<div class="meta-item">
							<span class="meta-label">Type</span>
							<span class="meta-value type">{node.documentation.dataType}</span>
						</div>
					{/if}
					{#if node.documentation.required !== undefined}
						<div class="meta-item">
							<span class="meta-label">Required</span>
							<span class="meta-value" class:yes={node.documentation.required} class:no={!node.documentation.required}>
								{node.documentation.required ? 'Yes' : 'No'}
							</span>
						</div>
					{/if}
					{#if node.documentation.repeatable !== undefined}
						<div class="meta-item">
							<span class="meta-label">Repeatable</span>
							<span class="meta-value" class:yes={node.documentation.repeatable} class:no={!node.documentation.repeatable}>
								{node.documentation.repeatable ? 'Yes' : 'No'}
							</span>
						</div>
					{/if}
				</div>

				{#if node.documentation.vocabulary}
					<div class="doc-section">
						<div class="doc-label">Vocabulary</div>
						<div class="doc-value vocab">
							<a href={node.documentation.vocabulary} target="_blank" rel="noopener noreferrer">
								{node.documentation.vocabulary}
							</a>
						</div>
					</div>
				{/if}

				{#if node.documentation.examples?.length}
					<div class="doc-section">
						<div class="doc-label">Examples</div>
						<div class="examples-list">
							{#each node.documentation.examples as example}
								<code class="example">{example}</code>
							{/each}
						</div>
					</div>
				{/if}

				{#if node.documentation.notes}
					<div class="doc-section">
						<div class="doc-label">Notes</div>
						<div class="doc-value notes">{node.documentation.notes}</div>
					</div>
				{/if}
			</div>
		{/if}
	{:else}
		<div class="empty-state">
			<div class="empty-icon">
				<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
					<path d="M9 17.25v1.007a3 3 0 01-.879 2.122L7.5 21h9l-.621-.621A3 3 0 0115 18.257V17.25m6-12V15a2.25 2.25 0 01-2.25 2.25H5.25A2.25 2.25 0 013 15V5.25m18 0A2.25 2.25 0 0018.75 3H5.25A2.25 2.25 0 003 5.25m18 0V12a2.25 2.25 0 01-2.25 2.25H5.25A2.25 2.25 0 013 12V5.25" />
				</svg>
			</div>
			<div class="empty-text">Select a field to view details</div>
			<div class="empty-hint">Click on a source or target field</div>
		</div>
	{/if}
</div>

<style>
	.field-stats {
		height: 100%;
		display: flex;
		flex-direction: column;
		padding: 12px;
		overflow: hidden;
	}

	.stats-header {
		margin-bottom: 12px;
	}

	.stats-title {
		display: flex;
		align-items: center;
		gap: 8px;
		margin-bottom: 4px;
	}

	.stats-type {
		font-size: 10px;
		text-transform: uppercase;
		color: #9ca3af;
		background: #374151;
		padding: 2px 6px;
		border-radius: 4px;
	}

	.stats-name {
		font-size: 14px;
		font-weight: 600;
		color: #f3f4f6;
	}

	.stats-path {
		font-size: 11px;
		color: #6b7280;
		font-family: monospace;
	}

	/* Tabs */
	.tabs {
		display: flex;
		gap: 4px;
		margin-bottom: 12px;
		border-bottom: 1px solid #374151;
		padding-bottom: 8px;
	}

	.tab {
		padding: 6px 12px;
		font-size: 12px;
		font-weight: 500;
		color: #9ca3af;
		background: transparent;
		border: 1px solid transparent;
		border-radius: 6px;
		cursor: pointer;
		transition: all 0.15s ease;
	}

	.tab:hover:not(.disabled) {
		color: #f3f4f6;
		background: #1f2937;
	}

	.tab.active {
		color: #f3f4f6;
		background: #374151;
		border-color: #4b5563;
	}

	.tab.disabled {
		opacity: 0.4;
		cursor: not-allowed;
	}

	.tab-content {
		flex: 1;
		overflow: auto;
	}

	/* Stats Tab */
	.stats-summary {
		display: grid;
		grid-template-columns: repeat(3, 1fr);
		gap: 8px;
		margin-bottom: 16px;
	}

	.stat-item {
		background: #1f2937;
		padding: 8px;
		border-radius: 6px;
		text-align: center;
	}

	.stat-label {
		display: block;
		font-size: 10px;
		color: #9ca3af;
		margin-bottom: 4px;
	}

	.stat-value {
		display: block;
		font-size: 13px;
		font-weight: 600;
		color: #f3f4f6;
	}

	.stat-value.mapped {
		color: #4ade80;
	}

	.histogram-section {
		flex: 1;
		min-height: 0;
		display: flex;
		flex-direction: column;
	}

	.histogram-header {
		font-size: 11px;
		text-transform: uppercase;
		color: #9ca3af;
		margin-bottom: 8px;
	}

	.histogram {
		flex: 1;
		overflow: auto;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}

	.histogram-row {
		display: flex;
		align-items: center;
		gap: 8px;
		font-size: 12px;
	}

	.histogram-value {
		width: 120px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: #d1d5db;
	}

	.histogram-bar-container {
		flex: 1;
		height: 16px;
		background: #1f2937;
		border-radius: 4px;
		overflow: hidden;
	}

	.histogram-bar {
		height: 100%;
		background: linear-gradient(90deg, #3b82f6, #60a5fa);
		border-radius: 4px;
		transition: width 0.3s ease;
	}

	.histogram-count {
		width: 40px;
		text-align: right;
		color: #9ca3af;
		font-size: 11px;
	}

	.mapping-info {
		margin-top: 16px;
		padding-top: 16px;
		border-top: 1px solid #374151;
	}

	.mapping-header {
		font-size: 11px;
		text-transform: uppercase;
		color: #9ca3af;
		margin-bottom: 8px;
	}

	.mapping-code {
		background: #1f2937;
		padding: 8px 12px;
		border-radius: 6px;
		font-family: monospace;
		font-size: 12px;
		color: #4ade80;
	}

	/* Documentation Tab */
	.docs-content {
		display: flex;
		flex-direction: column;
		gap: 16px;
	}

	.doc-section {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}

	.doc-label {
		font-size: 10px;
		text-transform: uppercase;
		color: #9ca3af;
		letter-spacing: 0.05em;
	}

	.doc-value {
		font-size: 13px;
		color: #e5e7eb;
		line-height: 1.5;
	}

	.doc-value.vocab a {
		color: #60a5fa;
		text-decoration: none;
		word-break: break-all;
	}

	.doc-value.vocab a:hover {
		text-decoration: underline;
	}

	.doc-value.notes {
		color: #9ca3af;
		font-style: italic;
	}

	.doc-meta {
		display: flex;
		flex-wrap: wrap;
		gap: 12px;
	}

	.meta-item {
		display: flex;
		align-items: center;
		gap: 6px;
		background: #1f2937;
		padding: 6px 10px;
		border-radius: 6px;
	}

	.meta-label {
		font-size: 11px;
		color: #9ca3af;
	}

	.meta-value {
		font-size: 12px;
		font-weight: 500;
		color: #f3f4f6;
	}

	.meta-value.type {
		font-family: monospace;
		color: #a78bfa;
	}

	.meta-value.yes {
		color: #4ade80;
	}

	.meta-value.no {
		color: #6b7280;
	}

	.examples-list {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}

	.example {
		background: #1f2937;
		padding: 4px 8px;
		border-radius: 4px;
		font-size: 12px;
		color: #fbbf24;
	}

	/* Empty State */
	.empty-state {
		height: 100%;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		color: #6b7280;
	}

	.empty-icon {
		width: 48px;
		height: 48px;
		margin-bottom: 12px;
		opacity: 0.5;
	}

	.empty-icon svg {
		width: 100%;
		height: 100%;
	}

	.empty-text {
		font-size: 14px;
		margin-bottom: 4px;
	}

	.empty-hint {
		font-size: 12px;
		color: #4b5563;
	}
</style>
