<script lang="ts">
	import type { TreeNode, FieldStatus, QualitySummary } from '$lib/types';
	import {
		loadHistogram,
		loadFieldStatus,
		loadQualitySummary,
		getNextBracketSize,
		getHistogramDownloadUrl,
		type HistogramData
	} from '$lib/api/mappingEditor';

	interface Props {
		node: TreeNode | null;
		datasetSpec?: string | null;
		onNavigateToPath?: (path: string) => void;
	}

	let { node, datasetSpec = null, onNavigateToPath }: Props = $props();

	// View mode: 'field' shows field stats, 'dataset' shows dataset overview
	let viewMode = $state<'field' | 'dataset'>('dataset');

	// Dataset quality summary
	let qualitySummary = $state<QualitySummary | null>(null);
	let isLoadingSummary = $state(false);
	let summaryError = $state<string | null>(null);

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

	// Histogram data (fetched from API for source nodes)
	let histogramData = $state<HistogramData | null>(null);
	let currentBracketSize = $state(100);
	let isLoadingHistogram = $state(false);
	let histogramError = $state<string | null>(null);

	// Field status (type detection, value stats, issues)
	let fieldStatus = $state<FieldStatus | null>(null);
	let isLoadingStatus = $state(false);
	let statusError = $state<string | null>(null);

	// Load quality summary when dataset changes
	$effect(() => {
		if (datasetSpec) {
			fetchQualitySummary();
		} else {
			qualitySummary = null;
			summaryError = null;
		}
	});

	// Switch to field view and load data when node is selected
	$effect(() => {
		if (node && datasetSpec && node.hasValues) {
			viewMode = 'field';
			currentBracketSize = 100;
			fetchHistogram(100);
			fetchFieldStatus();
		} else if (node) {
			// Node selected but no values - still show field view
			viewMode = 'field';
			histogramData = null;
			histogramError = null;
			fieldStatus = null;
			statusError = null;
		}
	});

	async function fetchQualitySummary() {
		if (!datasetSpec) return;

		isLoadingSummary = true;
		summaryError = null;
		try {
			qualitySummary = await loadQualitySummary(datasetSpec);
		} catch (err) {
			summaryError = err instanceof Error ? err.message : 'Failed to load quality summary';
			qualitySummary = null;
		} finally {
			isLoadingSummary = false;
		}
	}

	function showDatasetOverview() {
		viewMode = 'dataset';
	}

	function getScoreColor(score: number): string {
		if (score >= 90) return '#4ade80';
		if (score >= 70) return '#fbbf24';
		if (score >= 50) return '#fb923c';
		return '#f87171';
	}

	function getScoreCategory(score: number): string {
		if (score >= 90) return 'Excellent';
		if (score >= 70) return 'Good';
		if (score >= 50) return 'Fair';
		return 'Poor';
	}

	// Calculate total of completeness distribution
	const totalCompleteness = $derived(
		qualitySummary
			? qualitySummary.completenessDistribution.complete +
			  qualitySummary.completenessDistribution.high +
			  qualitySummary.completenessDistribution.medium +
			  qualitySummary.completenessDistribution.low
			: 0
	);

	function getDistPercent(value: number): string {
		return totalCompleteness > 0 ? ((value / totalCompleteness) * 100).toFixed(0) : '0';
	}

	function getDistWidth(value: number): number {
		return totalCompleteness > 0 ? (value / totalCompleteness) * 100 : 0;
	}

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

	async function fetchFieldStatus() {
		if (!node || !datasetSpec) return;

		isLoadingStatus = true;
		statusError = null;
		try {
			fieldStatus = await loadFieldStatus(datasetSpec, node.path);
		} catch (err) {
			statusError = err instanceof Error ? err.message : 'Failed to load field status';
			fieldStatus = null;
		} finally {
			isLoadingStatus = false;
		}
	}

	// Derived values
	const histogram = $derived(histogramData?.values ?? []);
	const maxCount = $derived(histogram.length > 0 ? Math.max(...histogram.map((h) => h.count)) : 1);
	const totalHistogramCount = $derived(histogram.reduce((sum, h) => sum + h.count, 0));
	const hasQuality = $derived(node?.quality);
	const canLoadMore = $derived(
		histogramData &&
		!histogramData.complete &&
		getNextBracketSize(currentBracketSize) !== null
	);
	const downloadUrl = $derived(
		node && datasetSpec ? getHistogramDownloadUrl(datasetSpec, node.path) : null
	);

	// Field status derived values
	const hasTypeInfo = $derived(fieldStatus?.typeInfo);
	const hasValueStats = $derived(fieldStatus?.valueStats);
	const hasWhitespaceIssues = $derived(fieldStatus?.valueStats?.whitespace && fieldStatus.valueStats.whitespace.total > 0);
	const hasEncodingIssues = $derived(fieldStatus?.valueStats?.encodingIssues && fieldStatus.valueStats.encodingIssues.total > 0);
	const hasOutliers = $derived(fieldStatus?.valueStats?.outliers && fieldStatus.valueStats.outliers.total > 0);
	const hasAnyIssues = $derived(hasWhitespaceIssues || hasEncodingIssues || hasOutliers);
	const hasUriValidation = $derived(fieldStatus?.patternInfo?.uriValidation);

	// Calculate uniqueness percentage
	const uniquenessPercent = $derived(() => {
		if (!node?.count || !histogramData?.uniqueCount) return 0;
		return (histogramData.uniqueCount / node.count) * 100;
	});
</script>

<div class="statistics-panel">
	{#if node && viewMode === 'field'}
		<!-- Field Stats Header -->
		<div class="panel-header">
			<div class="field-info">
				<div class="field-name-row">
					<button class="overview-btn" onclick={showDatasetOverview} title="Back to dataset overview">
						<svg viewBox="0 0 20 20" fill="currentColor">
							<path fill-rule="evenodd" d="M12.79 5.23a.75.75 0 01-.02 1.06L8.832 10l3.938 3.71a.75.75 0 11-1.04 1.08l-4.5-4.25a.75.75 0 010-1.08l4.5-4.25a.75.75 0 011.06.02z" clip-rule="evenodd"/>
						</svg>
					</button>
					<span class="field-name">{node.name}</span>
					<div class="header-badges">
						{#if node.count}
							<span class="stat-badge occurrences" title="Total occurrences in dataset">
								<svg class="badge-icon" viewBox="0 0 20 20" fill="currentColor">
									<path d="M2 10a8 8 0 018-8v8h8a8 8 0 11-16 0z"/>
									<path d="M12 2.252A8.014 8.014 0 0117.748 8H12V2.252z"/>
								</svg>
								{node.count.toLocaleString()} occurrences
							</span>
						{/if}
						{#if histogramData?.uniqueCount}
							<span class="stat-badge unique" title="Number of unique values">
								<svg class="badge-icon" viewBox="0 0 20 20" fill="currentColor">
									<path fill-rule="evenodd" d="M3 4a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm0 4a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm0 4a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm0 4a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1z" clip-rule="evenodd"/>
								</svg>
								{histogramData.uniqueCount.toLocaleString()} unique
							</span>
						{/if}
					</div>
				</div>
				<!-- Clickable breadcrumb path -->
				<nav class="path-breadcrumb" aria-label="Field path">
					{#each pathSegments as segment, i}
						{#if i > 0}
							<span class="path-separator">/</span>
						{/if}
						<button
							class="path-segment"
							class:current={i === pathSegments.length - 1}
							onclick={() => handlePathClick(segment)}
							title="Navigate to {segment.fullPath}"
						>
							{segment.name}
						</button>
					{/each}
				</nav>
			</div>
		</div>

		<!-- Main content grid -->
		<div class="content-grid">
			<!-- Left column: Histogram (main focus) -->
			<div class="histogram-section">
				<div class="section-header">
					<h3>Value Distribution</h3>
					{#if histogramData}
						<span class="count-info">
							{#if histogramData.complete}
								All {histogramData.uniqueCount.toLocaleString()} unique values
							{:else}
								Showing {histogramData.entries.toLocaleString()} of {histogramData.uniqueCount.toLocaleString()} unique values
							{/if}
						</span>
					{/if}
				</div>

				{#if isLoadingHistogram && !histogramData}
					<div class="loading-state">
						<div class="spinner"></div>
						<span>Loading histogram...</span>
					</div>
				{:else if histogramError}
					<div class="error-state">{histogramError}</div>
				{:else if histogram.length > 0}
					<div class="histogram-list">
						{#each histogram as item, i}
							<div class="histogram-row">
								<div class="row-rank">#{i + 1}</div>
								<div class="row-value" title={item.value}>{item.value}</div>
								<div class="row-bar-container">
									<div
										class="row-bar"
										style="width: {(item.count / maxCount) * 100}%"
									></div>
								</div>
								<div class="row-count">{item.count.toLocaleString()}</div>
								<div class="row-percent">
									{totalHistogramCount > 0 ? ((item.count / totalHistogramCount) * 100).toFixed(1) : 0}%
								</div>
							</div>
						{/each}
					</div>

					<!-- Actions -->
					<div class="histogram-actions">
						{#if canLoadMore}
							<button
								class="action-btn primary"
								onclick={loadMoreHistogram}
								disabled={isLoadingHistogram}
							>
								{#if isLoadingHistogram}
									<div class="spinner small"></div>
									Loading...
								{:else}
									Load more (up to {getNextBracketSize(currentBracketSize)?.toLocaleString()})
								{/if}
							</button>
						{/if}
						{#if downloadUrl}
							<a
								href={downloadUrl}
								class="action-btn secondary"
								download
								target="_blank"
								rel="noopener noreferrer"
							>
								<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
									<path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4M7 10l5 5 5-5M12 15V3"/>
								</svg>
								Download complete histogram
							</a>
						{/if}
					</div>
				{:else if node.hasValues}
					<div class="empty-state">No histogram data available</div>
				{:else}
					<div class="empty-state">This field has no text values (contains only child elements)</div>
				{/if}
			</div>

			<!-- Right column: Quality + Lengths + Samples -->
			<div class="sidebar">
				<!-- Quality Metrics -->
				{#if hasQuality && node.quality}
					<div class="sidebar-section quality-section">
						<h4>Data Quality</h4>
						<div class="quality-grid">
							<div class="quality-card">
								<div class="quality-value large" style="color: {node.quality.completeness >= 80 ? '#4ade80' : node.quality.completeness >= 50 ? '#fbbf24' : '#f87171'}">
									{node.quality.completeness.toFixed(1)}%
								</div>
								<div class="quality-label">Completeness</div>
							</div>
							<div class="quality-card">
								<div class="quality-value">{node.quality.avgPerRecord.toFixed(2)}</div>
								<div class="quality-label">Avg per Record</div>
							</div>
							<div class="quality-card">
								<div class="quality-value">{node.quality.recordsWithValue.toLocaleString()}</div>
								<div class="quality-label">Records with Value</div>
							</div>
							<div class="quality-card">
								<div class="quality-value">{node.quality.totalRecords.toLocaleString()}</div>
								<div class="quality-label">Total Records</div>
							</div>
						</div>

						{#if node.quality.emptyCount > 0}
							<div class="warning-box">
								<svg class="warning-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
									<path d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
								</svg>
								<span>{node.quality.emptyCount.toLocaleString()} empty values ({node.quality.emptyRate.toFixed(1)}%)</span>
							</div>
						{/if}
					</div>
				{/if}

				<!-- Length Distribution -->
				{#if node.lengths && node.lengths.length > 0}
					<div class="sidebar-section lengths-section">
						<h4>Value Lengths</h4>
						<div class="lengths-chart">
							{#each node.lengths as len}
								{@const totalLengths = node.lengths!.reduce((sum, l) => sum + l.count, 0)}
								{@const percent = (len.count / totalLengths) * 100}
								<div class="length-bar-row">
									<span class="length-range">{len.range}</span>
									<div class="length-bar-container">
										<div class="length-bar" style="width: {percent}%"></div>
									</div>
									<span class="length-count">{len.count.toLocaleString()}</span>
								</div>
							{/each}
						</div>
					</div>
				{/if}

				<!-- Type Analysis -->
				{#if hasTypeInfo && fieldStatus?.typeInfo}
					<div class="sidebar-section type-section">
						<h4>Type Analysis</h4>
						<div class="type-info">
							<div class="type-badge" class:mixed={fieldStatus.typeInfo.isMixed}>
								{fieldStatus.typeInfo.dominantType}
							</div>
							<div class="type-consistency">
								{fieldStatus.typeInfo.consistency.toFixed(1)}% consistent
							</div>
						</div>
						{#if Object.keys(fieldStatus.typeInfo.distribution).length > 1}
							<div class="type-distribution">
								{#each Object.entries(fieldStatus.typeInfo.distribution) as [type, count]}
									<div class="type-dist-item">
										<span class="type-name">{type}</span>
										<span class="type-count">{count.toLocaleString()}</span>
									</div>
								{/each}
							</div>
						{/if}
					</div>
				{/if}

				<!-- Value Statistics -->
				{#if hasValueStats && fieldStatus?.valueStats}
					<div class="sidebar-section value-stats-section">
						<h4>Value Statistics</h4>
						<div class="stats-grid">
							<div class="stat-row">
								<span class="stat-label">Length</span>
								<span class="stat-range">
									{fieldStatus.valueStats.length.min} - {fieldStatus.valueStats.length.max}
									<span class="stat-avg">(avg: {fieldStatus.valueStats.length.avg.toFixed(1)})</span>
								</span>
							</div>
							<div class="stat-row">
								<span class="stat-label">Words</span>
								<span class="stat-range">
									{fieldStatus.valueStats.wordCount.min} - {fieldStatus.valueStats.wordCount.max}
									<span class="stat-avg">(avg: {fieldStatus.valueStats.wordCount.avg.toFixed(1)})</span>
								</span>
							</div>
							{#if fieldStatus.valueStats.numericRange}
								<div class="stat-row">
									<span class="stat-label">Numeric Range</span>
									<span class="stat-range">
										{fieldStatus.valueStats.numericRange.min.toLocaleString()} - {fieldStatus.valueStats.numericRange.max.toLocaleString()}
									</span>
								</div>
							{/if}
						</div>
					</div>
				{/if}

				<!-- Data Quality Issues -->
				{#if hasAnyIssues}
					<div class="sidebar-section issues-section">
						<h4>Data Quality Issues</h4>

						{#if hasWhitespaceIssues && fieldStatus?.valueStats?.whitespace}
							<div class="issue-card warning">
								<div class="issue-header">
									<svg class="issue-icon" viewBox="0 0 20 20" fill="currentColor">
										<path fill-rule="evenodd" d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 5a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 5zm0 9a1 1 0 100-2 1 1 0 000 2z" clip-rule="evenodd"/>
									</svg>
									<span class="issue-title">Whitespace Issues</span>
									<span class="issue-count">{fieldStatus.valueStats.whitespace.total}</span>
								</div>
								<div class="issue-details">
									{#if fieldStatus.valueStats.whitespace.leadingCount > 0}
										<div class="issue-item">• {fieldStatus.valueStats.whitespace.leadingCount} with leading whitespace</div>
									{/if}
									{#if fieldStatus.valueStats.whitespace.trailingCount > 0}
										<div class="issue-item">• {fieldStatus.valueStats.whitespace.trailingCount} with trailing whitespace</div>
									{/if}
									{#if fieldStatus.valueStats.whitespace.multipleSpacesCount > 0}
										<div class="issue-item">• {fieldStatus.valueStats.whitespace.multipleSpacesCount} with multiple spaces</div>
									{/if}
								</div>
								{#if fieldStatus.valueStats.whitespace.samples.length > 0}
									<div class="issue-samples">
										{#each fieldStatus.valueStats.whitespace.samples.slice(0, 2) as sample}
											<code class="sample-code">"{sample}"</code>
										{/each}
									</div>
								{/if}
							</div>
						{/if}

						{#if hasEncodingIssues && fieldStatus?.valueStats?.encodingIssues}
							<div class="issue-card error">
								<div class="issue-header">
									<svg class="issue-icon" viewBox="0 0 20 20" fill="currentColor">
										<path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-8-5a.75.75 0 01.75.75v4.5a.75.75 0 01-1.5 0v-4.5A.75.75 0 0110 5zm0 10a1 1 0 100-2 1 1 0 000 2z" clip-rule="evenodd"/>
									</svg>
									<span class="issue-title">Encoding Issues</span>
									<span class="issue-count">{fieldStatus.valueStats.encodingIssues.total}</span>
								</div>
								<div class="issue-details">
									{#if fieldStatus.valueStats.encodingIssues.mojibake > 0}
										<div class="issue-item">• {fieldStatus.valueStats.encodingIssues.mojibake} mojibake (garbled text)</div>
									{/if}
									{#if fieldStatus.valueStats.encodingIssues.htmlEntities > 0}
										<div class="issue-item">• {fieldStatus.valueStats.encodingIssues.htmlEntities} HTML entities</div>
									{/if}
									{#if fieldStatus.valueStats.encodingIssues.escapedChars > 0}
										<div class="issue-item">• {fieldStatus.valueStats.encodingIssues.escapedChars} escaped characters</div>
									{/if}
									{#if fieldStatus.valueStats.encodingIssues.controlChars > 0}
										<div class="issue-item">• {fieldStatus.valueStats.encodingIssues.controlChars} control characters</div>
									{/if}
									{#if fieldStatus.valueStats.encodingIssues.replacementChars > 0}
										<div class="issue-item">• {fieldStatus.valueStats.encodingIssues.replacementChars} replacement characters</div>
									{/if}
								</div>
								{#if fieldStatus.valueStats.encodingIssues.samples.length > 0}
									<div class="issue-samples">
										{#each fieldStatus.valueStats.encodingIssues.samples.slice(0, 2) as sample}
											<code class="sample-code">"{sample}"</code>
										{/each}
									</div>
								{/if}
							</div>
						{/if}

						{#if hasOutliers && fieldStatus?.valueStats?.outliers}
							<div class="issue-card info">
								<div class="issue-header">
									<svg class="issue-icon" viewBox="0 0 20 20" fill="currentColor">
										<path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a.75.75 0 000 1.5h.253a.25.25 0 01.244.304l-.459 2.066A1.75 1.75 0 0010.747 15H11a.75.75 0 000-1.5h-.253a.25.25 0 01-.244-.304l.459-2.066A1.75 1.75 0 009.253 9H9z" clip-rule="evenodd"/>
									</svg>
									<span class="issue-title">Date Outliers</span>
									<span class="issue-count">{fieldStatus.valueStats.outliers.total}</span>
								</div>
								<div class="issue-details">
									{#if fieldStatus.valueStats.outliers.futureDates > 0}
										<div class="issue-item">• {fieldStatus.valueStats.outliers.futureDates} future dates</div>
									{/if}
									{#if fieldStatus.valueStats.outliers.ancientDates > 0}
										<div class="issue-item">• {fieldStatus.valueStats.outliers.ancientDates} ancient dates</div>
									{/if}
									{#if fieldStatus.valueStats.outliers.suspiciousYears > 0}
										<div class="issue-item">• {fieldStatus.valueStats.outliers.suspiciousYears} suspicious years</div>
									{/if}
								</div>
								{#if fieldStatus.valueStats.outliers.samples.length > 0}
									<div class="issue-samples">
										{#each fieldStatus.valueStats.outliers.samples.slice(0, 2) as sample}
											<code class="sample-code">"{sample}"</code>
										{/each}
									</div>
								{/if}
							</div>
						{/if}
					</div>
				{/if}

				<!-- URI Validation -->
				{#if hasUriValidation && fieldStatus?.patternInfo?.uriValidation}
					<div class="sidebar-section uri-section">
						<h4>URI Validation</h4>
						<div class="uri-stats">
							<div class="uri-rate" class:good={fieldStatus.patternInfo.uriValidation.validRate >= 95} class:warning={fieldStatus.patternInfo.uriValidation.validRate >= 80 && fieldStatus.patternInfo.uriValidation.validRate < 95} class:bad={fieldStatus.patternInfo.uriValidation.validRate < 80}>
								{fieldStatus.patternInfo.uriValidation.validRate.toFixed(1)}% valid
							</div>
							<div class="uri-counts">
								<span class="valid-count">{fieldStatus.patternInfo.uriValidation.valid.toLocaleString()} valid</span>
								<span class="invalid-count">{fieldStatus.patternInfo.uriValidation.invalid.toLocaleString()} invalid</span>
							</div>
						</div>
						{#if fieldStatus.patternInfo.uriValidation.samples.length > 0}
							<div class="uri-samples">
								<div class="samples-label">Invalid examples:</div>
								{#each fieldStatus.patternInfo.uriValidation.samples.slice(0, 3) as sample}
									<code class="sample-code invalid-uri">{sample}</code>
								{/each}
							</div>
						{/if}
					</div>
				{/if}
			</div>
		</div>
	{:else}
		<!-- Dataset Quality Overview -->
		<div class="dataset-overview">
			<div class="overview-header">
				<h2>Dataset Quality Overview</h2>
				{#if datasetSpec}
					<span class="dataset-name">{datasetSpec}</span>
				{/if}
			</div>

			{#if isLoadingSummary}
				<div class="loading-state">
					<div class="spinner"></div>
					<span>Loading quality summary...</span>
				</div>
			{:else if summaryError}
				<div class="error-state">{summaryError}</div>
			{:else if qualitySummary}
				<div class="overview-content">
					<!-- Overall Score -->
					<div class="score-card">
						<div class="score-circle" style="--score-color: {getScoreColor(qualitySummary.overallScore)}; --score: {qualitySummary.overallScore}">
							<div class="score-value">{qualitySummary.overallScore.toFixed(0)}</div>
							<div class="score-label">Quality Score</div>
						</div>
						<div class="score-category" style="color: {getScoreColor(qualitySummary.overallScore)}">
							{getScoreCategory(qualitySummary.overallScore)}
						</div>
					</div>

					<!-- Key Metrics -->
					<div class="metrics-grid">
						<div class="metric-card">
							<div class="metric-value">{qualitySummary.totalRecords.toLocaleString()}</div>
							<div class="metric-label">Records</div>
						</div>
						<div class="metric-card">
							<div class="metric-value">{qualitySummary.leafFields}</div>
							<div class="metric-label">Fields</div>
						</div>
						<div class="metric-card">
							<div class="metric-value">{qualitySummary.fieldsInEveryRecord}</div>
							<div class="metric-label">Universal Fields</div>
						</div>
						<div class="metric-card" class:has-issues={qualitySummary.fieldsWithIssues > 0}>
							<div class="metric-value">{qualitySummary.fieldsWithIssues}</div>
							<div class="metric-label">Fields with Issues</div>
						</div>
					</div>

					<!-- Completeness Distribution -->
					<div class="distribution-section">
						<h3>Completeness Distribution</h3>
						<p class="dist-description">How many fields have values in what percentage of records</p>
						<div class="distribution-bars">
							<div class="dist-row">
								<span class="dist-label">Complete (100%)</span>
								<div class="dist-bar-container">
									<div class="dist-bar complete" style="width: {getDistWidth(qualitySummary.completenessDistribution.complete)}%"></div>
								</div>
								<span class="dist-count">{qualitySummary.completenessDistribution.complete}</span>
								<span class="dist-percent">{getDistPercent(qualitySummary.completenessDistribution.complete)}%</span>
							</div>
							<div class="dist-row">
								<span class="dist-label">High (80-99%)</span>
								<div class="dist-bar-container">
									<div class="dist-bar high" style="width: {getDistWidth(qualitySummary.completenessDistribution.high)}%"></div>
								</div>
								<span class="dist-count">{qualitySummary.completenessDistribution.high}</span>
								<span class="dist-percent">{getDistPercent(qualitySummary.completenessDistribution.high)}%</span>
							</div>
							<div class="dist-row">
								<span class="dist-label">Medium (50-79%)</span>
								<div class="dist-bar-container">
									<div class="dist-bar medium" style="width: {getDistWidth(qualitySummary.completenessDistribution.medium)}%"></div>
								</div>
								<span class="dist-count">{qualitySummary.completenessDistribution.medium}</span>
								<span class="dist-percent">{getDistPercent(qualitySummary.completenessDistribution.medium)}%</span>
							</div>
							<div class="dist-row">
								<span class="dist-label">Low (&lt;50%)</span>
								<div class="dist-bar-container">
									<div class="dist-bar low" style="width: {getDistWidth(qualitySummary.completenessDistribution.low)}%"></div>
								</div>
								<span class="dist-count">{qualitySummary.completenessDistribution.low}</span>
								<span class="dist-percent">{getDistPercent(qualitySummary.completenessDistribution.low)}%</span>
							</div>
						</div>
					</div>

					<!-- Issues by Type -->
					{#if Object.keys(qualitySummary.issuesByType).length > 0}
						<div class="issues-section">
							<h3>Issues by Type</h3>
							<div class="issues-list">
								{#each Object.entries(qualitySummary.issuesByType) as [issueType, count]}
									<div class="issue-row">
										<span class="issue-type">{issueType.replace(/_/g, ' ')}</span>
										<span class="issue-field-count">{count} fields</span>
									</div>
								{/each}
							</div>
						</div>
					{/if}

					<!-- Problematic Fields -->
					{#if qualitySummary.problematicFields.length > 0}
						<div class="problematic-section">
							<h3>Problematic Fields</h3>
							<div class="problematic-list">
								{#each qualitySummary.problematicFields.slice(0, 10) as field}
									<button
										class="problematic-field"
										onclick={() => onNavigateToPath?.(field.path)}
									>
										<div class="field-header">
											<span class="field-tag">{field.tag}</span>
											<span class="field-score" style="color: {getScoreColor(field.qualityScore)}">
												{field.qualityScore.toFixed(0)}
											</span>
										</div>
										<div class="field-issues">
											{#each field.issues.slice(0, 3) as issue}
												<span class="issue-badge">{issue.replace(/_/g, ' ')}</span>
											{/each}
										</div>
									</button>
								{/each}
								{#if qualitySummary.problematicFields.length > 10}
									<div class="more-fields">
										+{qualitySummary.problematicFields.length - 10} more fields with issues
									</div>
								{/if}
							</div>
						</div>
					{/if}

					<!-- Hint -->
					<div class="overview-hint">
						Click on a field in the source tree to view detailed statistics
					</div>
				</div>
			{:else}
				<div class="no-data">
					<svg class="empty-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
						<path d="M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 013 19.875v-6.75zM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V8.625zM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V4.125z"/>
					</svg>
					<div class="empty-title">No quality data available</div>
					<div class="empty-hint">Select a dataset with analyzed source data</div>
				</div>
			{/if}
		</div>
	{/if}
</div>

<style>
	.statistics-panel {
		height: 100%;
		display: flex;
		flex-direction: column;
		background: #111827;
		overflow: hidden;
	}

	/* Header */
	.panel-header {
		padding: 16px 20px;
		background: #1f2937;
		border-bottom: 1px solid #374151;
	}

	.field-info {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}

	.field-name-row {
		display: flex;
		align-items: center;
		gap: 12px;
		flex-wrap: wrap;
	}

	.field-name {
		font-size: 18px;
		font-weight: 600;
		color: #f3f4f6;
	}

	.header-badges {
		display: flex;
		align-items: center;
		gap: 8px;
		flex-wrap: wrap;
	}

	.stat-badge {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 4px 10px;
		background: #374151;
		border-radius: 12px;
		font-size: 12px;
		color: #9ca3af;
	}

	.stat-badge.occurrences {
		background: rgba(59, 130, 246, 0.15);
		color: #60a5fa;
	}

	.stat-badge.unique {
		background: rgba(168, 85, 247, 0.15);
		color: #a78bfa;
	}

	.badge-icon {
		width: 12px;
		height: 12px;
		opacity: 0.8;
	}

	/* Breadcrumb path */
	.path-breadcrumb {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 2px;
		font-family: ui-monospace, monospace;
		font-size: 12px;
	}

	.path-separator {
		color: #4b5563;
		user-select: none;
	}

	.path-segment {
		padding: 2px 6px;
		background: transparent;
		border: none;
		border-radius: 4px;
		color: #9ca3af;
		cursor: pointer;
		transition: all 0.15s;
		font-family: inherit;
		font-size: inherit;
	}

	.path-segment:hover {
		background: #374151;
		color: #e5e7eb;
	}

	.path-segment.current {
		background: #3b82f6;
		color: white;
		cursor: default;
	}

	.path-segment.current:hover {
		background: #3b82f6;
	}

	/* Content grid */
	.content-grid {
		flex: 1;
		display: grid;
		grid-template-columns: 1fr 380px;
		gap: 1px;
		background: #374151;
		overflow: hidden;
	}

	/* Histogram section (left) */
	.histogram-section {
		display: flex;
		flex-direction: column;
		background: #111827;
		padding: 20px;
		overflow: hidden;
	}

	.section-header {
		display: flex;
		justify-content: space-between;
		align-items: center;
		margin-bottom: 16px;
	}

	.section-header h3 {
		font-size: 14px;
		font-weight: 600;
		color: #e5e7eb;
		margin: 0;
	}

	.count-info {
		font-size: 12px;
		color: #9ca3af;
	}

	.histogram-list {
		flex: 1;
		overflow: auto;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}

	.histogram-row {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 8px 12px;
		background: #1f2937;
		border-radius: 8px;
		transition: background 0.15s;
	}

	.histogram-row:hover {
		background: #283548;
	}

	.row-rank {
		font-size: 11px;
		color: #6b7280;
		min-width: 32px;
	}

	.row-value {
		flex: 0 0 200px;
		font-size: 13px;
		color: #e5e7eb;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-family: ui-monospace, monospace;
	}

	.row-bar-container {
		flex: 1;
		height: 20px;
		background: #374151;
		border-radius: 4px;
		overflow: hidden;
	}

	.row-bar {
		height: 100%;
		background: linear-gradient(90deg, #3b82f6, #60a5fa);
		border-radius: 4px;
		transition: width 0.3s ease;
	}

	.row-count {
		font-size: 13px;
		font-weight: 500;
		color: #e5e7eb;
		min-width: 60px;
		text-align: right;
	}

	.row-percent {
		font-size: 12px;
		color: #9ca3af;
		min-width: 50px;
		text-align: right;
	}

	.histogram-actions {
		display: flex;
		gap: 12px;
		margin-top: 16px;
		padding-top: 16px;
		border-top: 1px solid #374151;
	}

	.action-btn {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		padding: 10px 16px;
		font-size: 13px;
		font-weight: 500;
		border-radius: 8px;
		cursor: pointer;
		transition: all 0.15s;
		text-decoration: none;
		border: none;
	}

	.action-btn.primary {
		background: #3b82f6;
		color: white;
	}

	.action-btn.primary:hover:not(:disabled) {
		background: #2563eb;
	}

	.action-btn.primary:disabled {
		opacity: 0.7;
		cursor: wait;
	}

	.action-btn.secondary {
		background: #374151;
		color: #e5e7eb;
		border: 1px solid #4b5563;
	}

	.action-btn.secondary:hover {
		background: #4b5563;
	}

	.action-btn .icon {
		width: 16px;
		height: 16px;
	}

	/* Sidebar */
	.sidebar {
		display: flex;
		flex-direction: column;
		background: #111827;
		overflow: auto;
	}

	.sidebar-section {
		padding: 20px;
		border-bottom: 1px solid #374151;
	}

	.sidebar-section:last-child {
		border-bottom: none;
	}

	.sidebar-section h4 {
		font-size: 12px;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: #9ca3af;
		margin: 0 0 12px 0;
	}

	/* Quality metrics */
	.quality-grid {
		display: grid;
		grid-template-columns: repeat(2, 1fr);
		gap: 12px;
	}

	.quality-card {
		background: #1f2937;
		padding: 12px;
		border-radius: 8px;
		text-align: center;
	}

	.quality-value {
		font-size: 20px;
		font-weight: 700;
		color: #f3f4f6;
		margin-bottom: 4px;
	}

	.quality-value.large {
		font-size: 28px;
	}

	.quality-label {
		font-size: 11px;
		color: #9ca3af;
	}

	.warning-box {
		display: flex;
		align-items: center;
		gap: 8px;
		margin-top: 12px;
		padding: 10px 12px;
		background: #422006;
		border: 1px solid #854d0e;
		border-radius: 8px;
		font-size: 12px;
		color: #fbbf24;
	}

	.warning-icon {
		width: 18px;
		height: 18px;
		flex-shrink: 0;
	}

	/* Lengths */
	.lengths-chart {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}

	.length-bar-row {
		display: flex;
		align-items: center;
		gap: 12px;
	}

	.length-range {
		font-size: 12px;
		color: #9ca3af;
		min-width: 60px;
		font-family: ui-monospace, monospace;
	}

	.length-bar-container {
		flex: 1;
		height: 12px;
		background: #374151;
		border-radius: 4px;
		overflow: hidden;
	}

	.length-bar {
		height: 100%;
		background: linear-gradient(90deg, #8b5cf6, #a78bfa);
		border-radius: 4px;
	}

	.length-count {
		font-size: 12px;
		color: #e5e7eb;
		min-width: 50px;
		text-align: right;
	}

	/* Type Analysis */
	.type-info {
		display: flex;
		align-items: center;
		gap: 12px;
		margin-bottom: 12px;
	}

	.type-badge {
		display: inline-flex;
		padding: 4px 12px;
		background: #3b82f6;
		color: white;
		border-radius: 16px;
		font-size: 12px;
		font-weight: 600;
		text-transform: uppercase;
	}

	.type-badge.mixed {
		background: #f59e0b;
	}

	.type-consistency {
		font-size: 12px;
		color: #9ca3af;
	}

	.type-distribution {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}

	.type-dist-item {
		display: flex;
		justify-content: space-between;
		font-size: 12px;
		padding: 6px 10px;
		background: #1f2937;
		border-radius: 6px;
	}

	.type-name {
		color: #9ca3af;
		text-transform: lowercase;
	}

	.type-count {
		color: #e5e7eb;
	}

	/* Value Statistics */
	.stats-grid {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}

	.stat-row {
		display: flex;
		justify-content: space-between;
		align-items: center;
		padding: 8px 10px;
		background: #1f2937;
		border-radius: 6px;
	}

	.stat-label {
		font-size: 12px;
		color: #9ca3af;
	}

	.stat-range {
		font-size: 12px;
		color: #e5e7eb;
		font-family: ui-monospace, monospace;
	}

	.stat-avg {
		color: #6b7280;
		margin-left: 4px;
	}

	/* Issue Cards */
	.issue-card {
		padding: 12px;
		border-radius: 8px;
		margin-bottom: 10px;
	}

	.issue-card:last-child {
		margin-bottom: 0;
	}

	.issue-card.warning {
		background: rgba(251, 191, 36, 0.1);
		border: 1px solid rgba(251, 191, 36, 0.3);
	}

	.issue-card.error {
		background: rgba(239, 68, 68, 0.1);
		border: 1px solid rgba(239, 68, 68, 0.3);
	}

	.issue-card.info {
		background: rgba(59, 130, 246, 0.1);
		border: 1px solid rgba(59, 130, 246, 0.3);
	}

	.issue-header {
		display: flex;
		align-items: center;
		gap: 8px;
		margin-bottom: 8px;
	}

	.issue-icon {
		width: 16px;
		height: 16px;
		flex-shrink: 0;
	}

	.issue-card.warning .issue-icon {
		color: #fbbf24;
	}

	.issue-card.error .issue-icon {
		color: #ef4444;
	}

	.issue-card.info .issue-icon {
		color: #3b82f6;
	}

	.issue-title {
		font-size: 12px;
		font-weight: 600;
		flex: 1;
	}

	.issue-card.warning .issue-title {
		color: #fbbf24;
	}

	.issue-card.error .issue-title {
		color: #ef4444;
	}

	.issue-card.info .issue-title {
		color: #3b82f6;
	}

	.issue-count {
		font-size: 11px;
		padding: 2px 8px;
		background: rgba(0, 0, 0, 0.2);
		border-radius: 10px;
		color: #9ca3af;
	}

	.issue-details {
		margin-left: 24px;
	}

	.issue-item {
		font-size: 11px;
		color: #9ca3af;
		line-height: 1.6;
	}

	.issue-samples {
		margin-top: 8px;
		margin-left: 24px;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}

	.sample-code {
		font-size: 11px;
		font-family: ui-monospace, monospace;
		padding: 4px 8px;
		background: rgba(0, 0, 0, 0.3);
		border-radius: 4px;
		color: #e5e7eb;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	/* URI Validation */
	.uri-stats {
		display: flex;
		align-items: center;
		gap: 16px;
		margin-bottom: 12px;
	}

	.uri-rate {
		font-size: 18px;
		font-weight: 700;
	}

	.uri-rate.good {
		color: #4ade80;
	}

	.uri-rate.warning {
		color: #fbbf24;
	}

	.uri-rate.bad {
		color: #f87171;
	}

	.uri-counts {
		display: flex;
		flex-direction: column;
		gap: 2px;
		font-size: 11px;
	}

	.valid-count {
		color: #4ade80;
	}

	.invalid-count {
		color: #f87171;
	}

	.uri-samples {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}

	.samples-label {
		font-size: 11px;
		color: #6b7280;
		margin-bottom: 4px;
	}

	.invalid-uri {
		color: #fca5a5;
	}

	/* Loading / Error / Empty states */
	.loading-state {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 12px;
		padding: 40px;
		color: #9ca3af;
		font-size: 14px;
	}

	.loading-state.small {
		padding: 20px;
		font-size: 12px;
	}

	.spinner {
		width: 24px;
		height: 24px;
		border: 2px solid #374151;
		border-top-color: #3b82f6;
		border-radius: 50%;
		animation: spin 0.8s linear infinite;
	}

	.spinner.small {
		width: 16px;
		height: 16px;
	}

	@keyframes spin {
		to { transform: rotate(360deg); }
	}

	.error-state {
		padding: 16px;
		background: #7f1d1d;
		color: #fca5a5;
		border-radius: 8px;
		font-size: 13px;
	}

	.error-state.small {
		padding: 12px;
		font-size: 12px;
	}

	.empty-state {
		padding: 40px;
		text-align: center;
		color: #6b7280;
		font-size: 13px;
	}

	.empty-state.small {
		padding: 20px;
		font-size: 12px;
	}

	/* No selection state */
	.no-selection {
		flex: 1;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		color: #6b7280;
	}

	.empty-icon {
		width: 64px;
		height: 64px;
		margin-bottom: 16px;
		opacity: 0.5;
	}

	.empty-title {
		font-size: 16px;
		font-weight: 500;
		color: #9ca3af;
		margin-bottom: 8px;
	}

	.empty-hint {
		font-size: 13px;
	}

	/* Overview button (back to dataset) */
	.overview-btn {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 28px;
		height: 28px;
		padding: 0;
		background: #374151;
		border: 1px solid #4b5563;
		border-radius: 6px;
		color: #9ca3af;
		cursor: pointer;
		transition: all 0.15s;
	}

	.overview-btn:hover {
		background: #4b5563;
		color: #e5e7eb;
	}

	.overview-btn svg {
		width: 16px;
		height: 16px;
	}

	/* Dataset Overview */
	.dataset-overview {
		flex: 1;
		display: flex;
		flex-direction: column;
		overflow: auto;
		padding: 24px;
	}

	.overview-header {
		display: flex;
		align-items: center;
		gap: 16px;
		margin-bottom: 24px;
	}

	.overview-header h2 {
		font-size: 20px;
		font-weight: 600;
		color: #f3f4f6;
		margin: 0;
	}

	.dataset-name {
		font-size: 14px;
		color: #9ca3af;
		padding: 4px 12px;
		background: #374151;
		border-radius: 16px;
	}

	.overview-content {
		display: flex;
		flex-direction: column;
		gap: 24px;
	}

	/* Score Card */
	.score-card {
		display: flex;
		align-items: center;
		gap: 24px;
		padding: 24px;
		background: #1f2937;
		border-radius: 16px;
	}

	.score-circle {
		position: relative;
		width: 120px;
		height: 120px;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		background: conic-gradient(
			var(--score-color, #4ade80) calc(var(--score, 0) * 1%),
			#374151 0
		);
		border-radius: 50%;
	}

	.score-circle::before {
		content: '';
		position: absolute;
		inset: 10px;
		background: #1f2937;
		border-radius: 50%;
	}

	.score-value {
		position: relative;
		font-size: 36px;
		font-weight: 700;
		color: #f3f4f6;
	}

	.score-circle .score-label {
		position: relative;
		font-size: 11px;
		color: #9ca3af;
		text-transform: uppercase;
		letter-spacing: 0.05em;
	}

	.score-category {
		font-size: 24px;
		font-weight: 600;
	}

	/* Metrics Grid */
	.metrics-grid {
		display: grid;
		grid-template-columns: repeat(4, 1fr);
		gap: 16px;
	}

	.metric-card {
		padding: 16px;
		background: #1f2937;
		border-radius: 12px;
		text-align: center;
	}

	.metric-card.has-issues {
		background: rgba(239, 68, 68, 0.15);
		border: 1px solid rgba(239, 68, 68, 0.3);
	}

	.metric-value {
		font-size: 28px;
		font-weight: 700;
		color: #f3f4f6;
		margin-bottom: 4px;
	}

	.metric-card.has-issues .metric-value {
		color: #f87171;
	}

	.metric-label {
		font-size: 12px;
		color: #9ca3af;
	}

	/* Distribution Section */
	.distribution-section {
		background: #1f2937;
		border-radius: 12px;
		padding: 20px;
	}

	.distribution-section h3 {
		font-size: 14px;
		font-weight: 600;
		color: #e5e7eb;
		margin: 0 0 8px 0;
	}

	.dist-description {
		font-size: 12px;
		color: #6b7280;
		margin: 0 0 16px 0;
	}

	.distribution-bars {
		display: flex;
		flex-direction: column;
		gap: 12px;
	}

	.dist-row {
		display: flex;
		align-items: center;
		gap: 12px;
	}

	.dist-label {
		font-size: 12px;
		color: #9ca3af;
		min-width: 120px;
	}

	.dist-bar-container {
		flex: 1;
		height: 16px;
		background: #374151;
		border-radius: 8px;
		overflow: hidden;
	}

	.dist-bar {
		height: 100%;
		border-radius: 8px;
		transition: width 0.3s ease;
	}

	.dist-bar.complete {
		background: linear-gradient(90deg, #4ade80, #22c55e);
	}

	.dist-bar.high {
		background: linear-gradient(90deg, #60a5fa, #3b82f6);
	}

	.dist-bar.medium {
		background: linear-gradient(90deg, #fbbf24, #f59e0b);
	}

	.dist-bar.low {
		background: linear-gradient(90deg, #f87171, #ef4444);
	}

	.dist-count {
		font-size: 12px;
		font-weight: 500;
		color: #e5e7eb;
		min-width: 36px;
		text-align: right;
	}

	.dist-percent {
		font-size: 11px;
		color: #6b7280;
		min-width: 32px;
		text-align: right;
	}

	/* Issues Section (dataset-level) */
	.dataset-overview .issues-section {
		background: #1f2937;
		border-radius: 12px;
		padding: 20px;
	}

	.dataset-overview .issues-section h3 {
		font-size: 14px;
		font-weight: 600;
		color: #e5e7eb;
		margin: 0 0 16px 0;
	}

	.issues-list {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}

	.issue-row {
		display: flex;
		justify-content: space-between;
		align-items: center;
		padding: 10px 14px;
		background: rgba(251, 191, 36, 0.1);
		border: 1px solid rgba(251, 191, 36, 0.2);
		border-radius: 8px;
	}

	.issue-type {
		font-size: 13px;
		color: #fbbf24;
		text-transform: capitalize;
	}

	.issue-field-count {
		font-size: 12px;
		color: #9ca3af;
	}

	/* Problematic Fields */
	.problematic-section {
		background: #1f2937;
		border-radius: 12px;
		padding: 20px;
	}

	.problematic-section h3 {
		font-size: 14px;
		font-weight: 600;
		color: #e5e7eb;
		margin: 0 0 16px 0;
	}

	.problematic-list {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}

	.problematic-field {
		display: flex;
		flex-direction: column;
		gap: 8px;
		padding: 12px 16px;
		background: #111827;
		border: 1px solid #374151;
		border-radius: 8px;
		cursor: pointer;
		transition: all 0.15s;
		text-align: left;
		width: 100%;
	}

	.problematic-field:hover {
		border-color: #4b5563;
		background: #1a2332;
	}

	.field-header {
		display: flex;
		justify-content: space-between;
		align-items: center;
	}

	.field-tag {
		font-size: 13px;
		font-weight: 500;
		color: #e5e7eb;
		font-family: ui-monospace, monospace;
	}

	.field-score {
		font-size: 14px;
		font-weight: 700;
	}

	.field-issues {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}

	.issue-badge {
		font-size: 10px;
		padding: 2px 8px;
		background: rgba(251, 191, 36, 0.15);
		color: #fbbf24;
		border-radius: 10px;
		text-transform: capitalize;
	}

	.more-fields {
		padding: 12px;
		text-align: center;
		font-size: 12px;
		color: #6b7280;
		background: #111827;
		border-radius: 8px;
		border: 1px dashed #374151;
	}

	/* Overview Hint */
	.overview-hint {
		text-align: center;
		padding: 16px;
		font-size: 13px;
		color: #6b7280;
		border-top: 1px solid #374151;
		margin-top: 8px;
	}

	/* No Data state */
	.no-data {
		flex: 1;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		color: #6b7280;
		padding: 40px;
	}

	/* Responsive adjustments */
	@media (max-width: 1200px) {
		.metrics-grid {
			grid-template-columns: repeat(2, 1fr);
		}
	}

	@media (max-width: 900px) {
		.content-grid {
			grid-template-columns: 1fr;
		}
	}
</style>
