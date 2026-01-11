<script lang="ts">
	import type { TreeNode } from '$lib/types';
	import {
		loadHistogram,
		loadSampleRecords,
		getNextBracketSize,
		getHistogramDownloadUrl,
		type HistogramData
	} from '$lib/api/mappingEditor';

	interface Props {
		node: TreeNode | null;
		type: 'source' | 'target';
		datasetSpec?: string | null;
		onNavigateToPath?: (path: string) => void;
	}

	let { node, type, datasetSpec = null, onNavigateToPath }: Props = $props();

	// Parse path into segments for breadcrumb display
	interface PathSegment {
		name: string;
		fullPath: string;
	}

	const pathSegments = $derived.by<PathSegment[]>(() => {
		if (!node?.path) return [];
		const parts = node.path.split('/').filter(Boolean);
		return parts.map((part, index) => ({
			name: part,
			fullPath: '/' + parts.slice(0, index + 1).join('/')
		}));
	});

	function handlePathClick(segment: PathSegment) {
		onNavigateToPath?.(segment.fullPath);
	}

	// Tab state
	let activeTab = $state<'stats' | 'samples' | 'docs'>('stats');

	// Histogram data (fetched from API for source nodes)
	let histogramData = $state<HistogramData | null>(null);
	let currentBracketSize = $state(100);
	let isLoadingHistogram = $state(false);
	let histogramError = $state<string | null>(null);

	// Sample values (fetched from API for source nodes)
	let samples = $state<string[]>([]);
	let isLoadingSamples = $state(false);
	let samplesError = $state<string | null>(null);

	// Automatically switch to docs tab when target node with documentation is selected
	$effect(() => {
		if (type === 'target' && node?.documentation) {
			activeTab = 'docs';
		} else if (type === 'source') {
			activeTab = 'stats';
		}
	});

	// Fetch histogram when source node changes
	$effect(() => {
		if (type === 'source' && node && datasetSpec && node.hasValues) {
			// Reset to smallest bracket when node changes
			currentBracketSize = 100;
			fetchHistogram(100);
		} else {
			histogramData = null;
			histogramError = null;
		}
	});

	async function fetchHistogram(size: number) {
		if (!node || !datasetSpec) return;

		isLoadingHistogram = true;
		histogramError = null;
		try {
			histogramData = await loadHistogram(datasetSpec, node.path, size);
			currentBracketSize = size;
		} catch (err) {
			histogramError = err instanceof Error ? err.message : 'Failed to load histogram';
			histogramData = null;
		} finally {
			isLoadingHistogram = false;
		}
	}

	async function loadMoreHistogram() {
		const nextSize = getNextBracketSize(currentBracketSize);
		if (nextSize) {
			await fetchHistogram(nextSize);
		}
	}

	async function fetchSamples() {
		if (!node || !datasetSpec) return;

		isLoadingSamples = true;
		samplesError = null;
		try {
			samples = await loadSampleRecords(datasetSpec, node.path, 100);
		} catch (err) {
			samplesError = err instanceof Error ? err.message : 'Failed to load samples';
			samples = [];
		} finally {
			isLoadingSamples = false;
		}
	}

	// Fetch samples when switching to samples tab
	$effect(() => {
		if (activeTab === 'samples' && type === 'source' && node && datasetSpec && samples.length === 0 && !isLoadingSamples) {
			fetchSamples();
		}
	});

	// Reset samples when node changes
	$effect(() => {
		node; // dependency
		samples = [];
		samplesError = null;
	});

	// Derived values
	const histogram = $derived(histogramData?.values ?? []);
	const maxCount = $derived(histogram.length > 0 ? Math.max(...histogram.map((h) => h.count)) : 1);
	const hasDocumentation = $derived(type === 'target' && node?.documentation);
	const hasQuality = $derived(type === 'source' && node?.quality);
	const canLoadMore = $derived(
		histogramData &&
		!histogramData.complete &&
		getNextBracketSize(currentBracketSize) !== null
	);
	const downloadUrl = $derived(
		node && datasetSpec ? getHistogramDownloadUrl(datasetSpec, node.path) : null
	);

	// Check if this is a special node without statistics
	const isSpecialNode = $derived(
		node?.isConstant || node?.isFacts || node?.isFact
	);
</script>

<div class="field-stats">
	{#if node && isSpecialNode}
		<!-- Special nodes: constant, facts, fact - no statistics available -->
		<div class="special-node-state">
			<div class="special-icon">
				{#if node.isConstant}
					<span class="icon-constant">"</span>
				{:else if node.isFacts}
					<span class="icon-facts">≡</span>
				{:else if node.isFact}
					<span class="icon-fact">$</span>
				{/if}
			</div>
			<div class="special-title">{node.name}</div>
			{#if node.isFact && node.factValue}
				<div class="special-value">{node.factValue}</div>
			{/if}
			<div class="special-hint">
				{#if node.isConstant}
					Drag this to a target field to map a constant value
				{:else if node.isFacts}
					Expand to see mapping facts
				{:else if node.isFact}
					Drag this to a target field to use this fact value
				{/if}
			</div>
		</div>
	{:else if node}
		<div class="stats-header">
			<div class="stats-title">
				<span class="stats-type">{type === 'source' ? 'Source' : 'Target'}</span>
				<span class="stats-name">{node.name}</span>
			</div>
			<!-- Badges row -->
			<div class="stats-badges">
				{#if node.count}
					<span class="mini-badge occurrences" title="Total occurrences">
						{node.count.toLocaleString()}
					</span>
				{/if}
				{#if histogramData?.uniqueCount}
					<span class="mini-badge unique" title="Unique values">
						{histogramData.uniqueCount.toLocaleString()} unique
					</span>
				{/if}
			</div>
			<!-- Clickable breadcrumb path -->
			<nav class="stats-path-nav" aria-label="Field path">
				{#each pathSegments as segment, i}
					{#if i > 0}
						<span class="path-sep">/</span>
					{/if}
					<button
						class="path-part"
						class:current={i === pathSegments.length - 1}
						onclick={() => handlePathClick(segment)}
						title="Go to {segment.fullPath}"
					>
						{segment.name}
					</button>
				{/each}
			</nav>
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
			{#if type === 'source' && node?.hasValues}
				<button
					class="tab"
					class:active={activeTab === 'samples'}
					onclick={() => (activeTab = 'samples')}
				>
					Samples
				</button>
			{/if}
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
					{#if hasQuality && node.quality}
						<div class="stat-item">
							<span class="stat-label">Completeness</span>
							<span class="stat-value completeness" style="--completeness: {node.quality.completeness}%">
								{node.quality.completeness.toFixed(1)}%
							</span>
						</div>
						<div class="stat-item">
							<span class="stat-label">Avg/Record</span>
							<span class="stat-value">{node.quality.avgPerRecord.toFixed(2)}</span>
						</div>
					{:else}
						<div class="stat-item">
							<span class="stat-label">Unique Values</span>
							<span class="stat-value">{histogram.length > 0 ? histogram.length.toLocaleString() : 'N/A'}</span>
						</div>
						<div class="stat-item">
							<span class="stat-label">Status</span>
							<span class="stat-value" class:mapped={node.mappedTo?.length || node.mappedFrom?.length}>
								{node.mappedTo?.length || node.mappedFrom?.length ? 'Mapped' : 'Unmapped'}
							</span>
						</div>
					{/if}
				</div>

				<!-- Quality details for source nodes -->
				{#if hasQuality && node.quality}
					<div class="quality-details">
						<div class="quality-row">
							<span class="quality-label">Records with value</span>
							<span class="quality-value">{node.quality.recordsWithValue.toLocaleString()} / {node.quality.totalRecords.toLocaleString()}</span>
						</div>
						{#if node.quality.emptyCount > 0}
							<div class="quality-row">
								<span class="quality-label">Empty values</span>
								<span class="quality-value warning">{node.quality.emptyCount.toLocaleString()} ({node.quality.emptyRate.toFixed(1)}%)</span>
							</div>
						{/if}
					</div>
				{/if}

				<!-- Length distribution -->
				{#if node.lengths && node.lengths.length > 0}
					<div class="lengths-section">
						<div class="section-header">Value Lengths</div>
						<div class="lengths-bars">
							{#each node.lengths as len}
								<div class="length-item">
									<span class="length-range">{len.range}</span>
									<span class="length-count">{len.count.toLocaleString()}</span>
								</div>
							{/each}
						</div>
					</div>
				{/if}

				<!-- Histogram (real data from API) -->
				<div class="histogram-section">
					<div class="histogram-header">
						<span class="histogram-title">
							Top Values
							{#if isLoadingHistogram}
								<span class="loading-indicator">Loading...</span>
							{/if}
						</span>
						{#if histogramData}
							<span class="histogram-count-info">
								{#if histogramData.complete}
									All {histogramData.uniqueCount.toLocaleString()} values
								{:else}
									{histogramData.entries.toLocaleString()} of {histogramData.uniqueCount.toLocaleString()}
								{/if}
							</span>
						{/if}
					</div>
					{#if histogramError}
						<div class="error-message">{histogramError}</div>
					{:else if histogram.length > 0}
						<div class="histogram">
							{#each histogram as item}
								<div class="histogram-row">
									<div class="histogram-value" title={item.value}>{item.value}</div>
									<div class="histogram-bar-container">
										<div
											class="histogram-bar"
											style="width: {(item.count / maxCount) * 100}%"
										></div>
									</div>
									<div class="histogram-count">{item.count.toLocaleString()}</div>
								</div>
							{/each}
						</div>

						<!-- Show more / Download actions -->
						<div class="histogram-actions">
							{#if canLoadMore}
								<button
									class="action-btn"
									onclick={loadMoreHistogram}
									disabled={isLoadingHistogram}
								>
									{#if isLoadingHistogram}
										Loading...
									{:else}
										Show more (up to {getNextBracketSize(currentBracketSize)?.toLocaleString()})
									{/if}
								</button>
							{/if}
							{#if downloadUrl}
								<a
									href={downloadUrl}
									class="action-btn download"
									download
									target="_blank"
									rel="noopener noreferrer"
								>
									<svg class="download-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
										<path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4M7 10l5 5 5-5M12 15V3"/>
									</svg>
									Download all
								</a>
							{/if}
						</div>
					{:else if !isLoadingHistogram && node.hasValues}
						<div class="no-data">No histogram data available</div>
					{:else if !node.hasValues}
						<div class="no-data">This field has no text values</div>
					{/if}
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

		<!-- Samples Tab Content -->
		{:else if activeTab === 'samples'}
			<div class="tab-content samples-content">
				{#if isLoadingSamples}
					<div class="loading-state">Loading sample values...</div>
				{:else if samplesError}
					<div class="error-message">{samplesError}</div>
				{:else if samples.length > 0}
					<div class="samples-list">
						{#each samples as sample, i}
							<div class="sample-item">
								<span class="sample-number">{i + 1}</span>
								<span class="sample-value">{sample}</span>
							</div>
						{/each}
					</div>
				{:else}
					<div class="no-data">No sample values available</div>
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
		margin-bottom: 6px;
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

	/* Badges */
	.stats-badges {
		display: flex;
		align-items: center;
		gap: 6px;
		margin-bottom: 6px;
	}

	.mini-badge {
		display: inline-flex;
		align-items: center;
		padding: 2px 6px;
		border-radius: 8px;
		font-size: 10px;
		font-weight: 500;
	}

	.mini-badge.occurrences {
		background: rgba(59, 130, 246, 0.15);
		color: #60a5fa;
	}

	.mini-badge.unique {
		background: rgba(168, 85, 247, 0.15);
		color: #a78bfa;
	}

	/* Breadcrumb path */
	.stats-path-nav {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 1px;
		font-family: ui-monospace, monospace;
		font-size: 10px;
		line-height: 1.6;
	}

	.path-sep {
		color: #4b5563;
		user-select: none;
	}

	.path-part {
		padding: 1px 4px;
		background: transparent;
		border: none;
		border-radius: 3px;
		color: #6b7280;
		cursor: pointer;
		transition: all 0.15s;
		font-family: inherit;
		font-size: inherit;
	}

	.path-part:hover {
		background: #374151;
		color: #e5e7eb;
	}

	.path-part.current {
		background: #3b82f6;
		color: white;
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

	.stat-value.completeness {
		color: #60a5fa;
	}

	/* Quality details */
	.quality-details {
		background: #1f2937;
		padding: 10px;
		border-radius: 6px;
		margin-bottom: 12px;
	}

	.quality-row {
		display: flex;
		justify-content: space-between;
		align-items: center;
		padding: 4px 0;
	}

	.quality-row:not(:last-child) {
		border-bottom: 1px solid #374151;
	}

	.quality-label {
		font-size: 11px;
		color: #9ca3af;
	}

	.quality-value {
		font-size: 12px;
		color: #e5e7eb;
		font-family: monospace;
	}

	.quality-value.warning {
		color: #fbbf24;
	}

	/* Lengths section */
	.lengths-section {
		margin-bottom: 12px;
	}

	.section-header {
		font-size: 11px;
		text-transform: uppercase;
		color: #9ca3af;
		margin-bottom: 6px;
	}

	.lengths-bars {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}

	.length-item {
		display: flex;
		align-items: center;
		gap: 4px;
		background: #1f2937;
		padding: 4px 8px;
		border-radius: 4px;
		font-size: 11px;
	}

	.length-range {
		color: #9ca3af;
	}

	.length-count {
		color: #e5e7eb;
		font-weight: 500;
	}

	/* Loading and error states */
	.loading-indicator {
		font-size: 10px;
		color: #60a5fa;
		margin-left: 8px;
		font-weight: normal;
	}

	.loading-state {
		padding: 20px;
		text-align: center;
		color: #9ca3af;
		font-size: 13px;
	}

	.error-message {
		padding: 10px;
		background: #7f1d1d;
		color: #fca5a5;
		border-radius: 6px;
		font-size: 12px;
		margin-bottom: 8px;
	}

	.no-data {
		padding: 16px;
		text-align: center;
		color: #6b7280;
		font-size: 12px;
	}

	/* Samples tab */
	.samples-content {
		padding: 0;
	}

	.samples-list {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}

	.sample-item {
		display: flex;
		align-items: flex-start;
		gap: 8px;
		padding: 8px;
		background: #1f2937;
		border-radius: 4px;
	}

	.sample-number {
		font-size: 10px;
		color: #6b7280;
		min-width: 20px;
	}

	.sample-value {
		font-size: 12px;
		color: #e5e7eb;
		word-break: break-all;
		font-family: monospace;
	}

	.histogram-section {
		flex: 1;
		min-height: 0;
		display: flex;
		flex-direction: column;
	}

	.histogram-header {
		display: flex;
		justify-content: space-between;
		align-items: center;
		margin-bottom: 8px;
	}

	.histogram-title {
		font-size: 11px;
		text-transform: uppercase;
		color: #9ca3af;
	}

	.histogram-count-info {
		font-size: 10px;
		color: #6b7280;
		font-weight: normal;
		text-transform: none;
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

	/* Histogram actions (show more, download) */
	.histogram-actions {
		display: flex;
		gap: 8px;
		margin-top: 12px;
		padding-top: 12px;
		border-top: 1px solid #374151;
	}

	.action-btn {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		padding: 6px 12px;
		font-size: 11px;
		font-weight: 500;
		color: #d1d5db;
		background: #374151;
		border: 1px solid #4b5563;
		border-radius: 6px;
		cursor: pointer;
		transition: all 0.15s ease;
		text-decoration: none;
	}

	.action-btn:hover:not(:disabled) {
		background: #4b5563;
		color: #f3f4f6;
	}

	.action-btn:disabled {
		opacity: 0.6;
		cursor: wait;
	}

	.action-btn.download {
		color: #60a5fa;
		border-color: #3b82f6;
	}

	.action-btn.download:hover {
		background: #1e3a5f;
		color: #93c5fd;
	}

	.download-icon {
		width: 14px;
		height: 14px;
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

	/* Special node state (constant, facts, fact) */
	.special-node-state {
		height: 100%;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		color: #9ca3af;
		text-align: center;
		padding: 20px;
	}

	.special-icon {
		margin-bottom: 12px;
	}

	.icon-constant {
		font-size: 32px;
		color: #a78bfa;
		font-family: serif;
		font-weight: bold;
	}

	.icon-facts {
		font-size: 32px;
		color: #fbbf24;
	}

	.icon-fact {
		font-size: 32px;
		color: #34d399;
		font-weight: bold;
	}

	.special-title {
		font-size: 16px;
		font-weight: 600;
		color: #e5e7eb;
		margin-bottom: 8px;
	}

	.special-value {
		font-size: 14px;
		color: #60a5fa;
		font-family: ui-monospace, monospace;
		background: #1f2937;
		padding: 8px 16px;
		border-radius: 6px;
		margin-bottom: 12px;
		max-width: 100%;
		word-break: break-all;
	}

	.special-hint {
		font-size: 12px;
		color: #6b7280;
		max-width: 200px;
	}
</style>
