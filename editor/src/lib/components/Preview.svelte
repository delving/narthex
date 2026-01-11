<script lang="ts">
	import { PaneGroup, Pane, PaneResizer } from 'paneforge';
	import { sampleRecords, transformRecord, transformRecordWithMappings, recordToXml } from '$lib/sampleData';
	import { previewMappingByIndex, type PreviewMappingResponse } from '$lib/api/mappingEditor';
	import type { SampleRecord, OutputRecord } from '$lib/types';
	import type { Mapping } from '$lib/stores/mappingStore';

	interface Props {
		spec?: string; // Dataset spec - if provided, use server-side execution
		groovyCode?: string;
		mappings?: Mapping[];
		currentRecordIndex?: number;
		onRecordChange?: (index: number) => void;
		showShortcutHint?: boolean;
		totalRecordCount?: number; // Total records in dataset (for server mode)
		onMaximize?: () => void; // Callback to open fullscreen modal
	}

	let {
		spec,
		groovyCode,
		mappings = [],
		currentRecordIndex = 0,
		onRecordChange,
		showShortcutHint = false,
		totalRecordCount,
		onMaximize
	}: Props = $props();

	// Determine if we're using server-side execution
	const useServerExecution = $derived(!!spec);

	// Search state
	let searchQuery = $state('');
	let showSearch = $state(false);

	// Server-side execution state
	let serverLoading = $state(false);
	let serverError = $state<string | null>(null);
	let serverResult = $state<PreviewMappingResponse | null>(null);

	// Use prop if provided, otherwise local state
	let localIndex = $state(0);
	const currentIndex = $derived(onRecordChange ? currentRecordIndex : localIndex);

	// Total records depends on mode
	const totalRecords = $derived(useServerExecution ? (totalRecordCount ?? 0) : sampleRecords.length);

	// Load server preview when index changes (server mode only)
	$effect(() => {
		if (useServerExecution && spec) {
			loadServerPreview(currentIndex);
		}
	});

	async function loadServerPreview(index: number) {
		serverLoading = true;
		serverError = null;

		try {
			serverResult = await previewMappingByIndex(spec!, index);
			if (!serverResult.success && serverResult.error) {
				// Keep the result for input display, but show error
				serverError = `${serverResult.errorType || 'Error'}: ${serverResult.error}`;
			}
		} catch (err) {
			serverError = err instanceof Error ? err.message : 'Failed to load preview';
			serverResult = null;
		} finally {
			serverLoading = false;
		}
	}

	// Check if a record matches the search query (mock mode only)
	function recordMatchesSearch(record: SampleRecord, query: string): boolean {
		if (!query.trim()) return true;
		const lowerQuery = query.toLowerCase();

		function searchValue(value: unknown): boolean {
			if (typeof value === 'string') {
				return value.toLowerCase().includes(lowerQuery);
			} else if (typeof value === 'object' && value !== null) {
				return Object.values(value).some(searchValue);
			}
			return false;
		}

		return searchValue(record);
	}

	// Get indices of matching records (mock mode only)
	const matchingIndices = $derived.by(() => {
		if (useServerExecution) return [];
		if (!searchQuery.trim()) {
			return sampleRecords.map((_, i) => i);
		}
		return sampleRecords
			.map((record, index) => (recordMatchesSearch(record, searchQuery) ? index : -1))
			.filter((i) => i !== -1);
	});

	// Current position in filtered results (mock mode only)
	let filteredPosition = $state(0);

	// Jump to first matching record when search query changes (mock mode)
	$effect(() => {
		if (!useServerExecution && searchQuery.trim() && matchingIndices.length > 0) {
			const firstMatchIndex = matchingIndices[0];
			if (onRecordChange) {
				onRecordChange(firstMatchIndex);
			} else {
				localIndex = firstMatchIndex;
			}
			filteredPosition = 0;
		}
	});

	// Track position in filtered results (mock mode)
	$effect(() => {
		if (!useServerExecution && searchQuery.trim() && matchingIndices.length > 0) {
			const currentIdx = onRecordChange ? currentRecordIndex : localIndex;
			const pos = matchingIndices.indexOf(currentIdx);
			if (pos !== -1) {
				filteredPosition = pos;
			}
		}
	});

	const filteredCount = $derived(matchingIndices.length);

	// Mock mode: current record
	const currentRecord = $derived(!useServerExecution ? sampleRecords[currentIndex] : null);

	// Generated XML (mock mode)
	const inputXml = $derived.by(() => {
		if (useServerExecution) {
			return serverResult?.inputXml || '';
		}
		return currentRecord ? recordToXml(currentRecord) : '';
	});

	// Transformed output (mock mode uses local transform, server mode uses server response)
	const outputXml = $derived.by(() => {
		if (useServerExecution) {
			return serverResult?.outputXml || '';
		}
		// Mock mode: generate JSON output (we could convert to XML but JSON is fine for preview)
		return currentRecord
			? JSON.stringify(
					mappings.length > 0
						? transformRecordWithMappings(currentRecord, mappings)
						: transformRecord(currentRecord),
					null,
					2
				)
			: '';
	});

	// Record ID for display
	const recordId = $derived(useServerExecution ? serverResult?.recordId : null);

	// Navigation
	function prevRecord() {
		if (!useServerExecution && searchQuery.trim() && matchingIndices.length > 0) {
			if (filteredPosition > 0) {
				const newPos = filteredPosition - 1;
				const newIndex = matchingIndices[newPos];
				if (onRecordChange) {
					onRecordChange(newIndex);
				} else {
					localIndex = newIndex;
				}
				filteredPosition = newPos;
			}
		} else {
			if (currentIndex > 0) {
				if (onRecordChange) {
					onRecordChange(currentIndex - 1);
				} else {
					localIndex--;
				}
			}
		}
	}

	function nextRecord() {
		if (!useServerExecution && searchQuery.trim() && matchingIndices.length > 0) {
			if (filteredPosition < matchingIndices.length - 1) {
				const newPos = filteredPosition + 1;
				const newIndex = matchingIndices[newPos];
				if (onRecordChange) {
					onRecordChange(newIndex);
				} else {
					localIndex = newIndex;
				}
				filteredPosition = newPos;
			}
		} else {
			if (currentIndex < totalRecords - 1) {
				if (onRecordChange) {
					onRecordChange(currentIndex + 1);
				} else {
					localIndex++;
				}
			}
		}
	}

	// Check if can navigate
	const canGoPrev = $derived.by(() => {
		if (!useServerExecution && searchQuery.trim()) return filteredPosition > 0;
		return currentIndex > 0;
	});

	const canGoNext = $derived.by(() => {
		if (!useServerExecution && searchQuery.trim())
			return filteredPosition < matchingIndices.length - 1;
		return currentIndex < totalRecords - 1;
	});

	function toggleSearch() {
		showSearch = !showSearch;
		if (!showSearch) {
			searchQuery = '';
		}
	}

	function clearSearch() {
		searchQuery = '';
	}

	// Escape HTML entities
	function escapeHtml(str: string): string {
		return str
			.replace(/&/g, '&amp;')
			.replace(/</g, '&lt;')
			.replace(/>/g, '&gt;')
			.replace(/"/g, '&quot;');
	}

	// Highlight search matches in text
	function highlightSearchMatches(text: string, query: string): string {
		if (!query.trim()) return text;

		const lowerText = text.toLowerCase();
		const lowerQuery = query.toLowerCase();
		const result: string[] = [];
		let lastIndex = 0;
		let index = lowerText.indexOf(lowerQuery);

		while (index !== -1) {
			if (index > lastIndex) {
				result.push(text.slice(lastIndex, index));
			}
			result.push(
				`<mark class="search-match">${text.slice(index, index + query.length)}</mark>`
			);
			lastIndex = index + query.length;
			index = lowerText.indexOf(lowerQuery, lastIndex);
		}

		if (lastIndex < text.length) {
			result.push(text.slice(lastIndex));
		}

		return result.join('');
	}

	// Syntax highlight XML
	function highlightXml(xml: string, searchQuery: string = ''): string {
		const result: string[] = [];
		let i = 0;

		while (i < xml.length) {
			if (xml[i] === '<') {
				const tagEnd = xml.indexOf('>', i);
				if (tagEnd === -1) break;

				const tagContent = xml.slice(i + 1, tagEnd);
				const isClosing = tagContent.startsWith('/');
				const isSelfClosing = tagContent.endsWith('/');

				const tagStr = isClosing ? tagContent.slice(1) : tagContent;
				const cleanTag = isSelfClosing ? tagStr.slice(0, -1).trim() : tagStr;

				const spaceIdx = cleanTag.indexOf(' ');
				let tagName: string;
				let attrs = '';

				if (spaceIdx > -1) {
					tagName = cleanTag.slice(0, spaceIdx);
					attrs = cleanTag.slice(spaceIdx);
				} else {
					tagName = cleanTag;
				}

				let highlighted = '&lt;';
				if (isClosing) highlighted += '/';
				highlighted += `<span class="xml-tag">${escapeHtml(tagName)}</span>`;

				if (attrs) {
					attrs = attrs.replace(
						/(\s+)([\w\-.:]+)(=)("([^"]*)")/g,
						(_, space, name, eq, fullValue, innerValue) => {
							const escapedValue = escapeHtml(fullValue);
							const highlightedValue = searchQuery
								? highlightSearchMatches(escapedValue, searchQuery)
								: escapedValue;
							return `${space}<span class="xml-attr">${escapeHtml(name)}</span>=<span class="xml-value">${highlightedValue}</span>`;
						}
					);
					highlighted += attrs;
				}

				if (isSelfClosing && !isClosing) highlighted += ' /';
				highlighted += '&gt;';

				result.push(highlighted);
				i = tagEnd + 1;
			} else {
				const nextTag = xml.indexOf('<', i);
				const textEnd = nextTag === -1 ? xml.length : nextTag;
				const text = xml.slice(i, textEnd);
				const escapedText = escapeHtml(text);
				const highlightedText = searchQuery
					? highlightSearchMatches(escapedText, searchQuery)
					: escapedText;
				result.push(highlightedText);
				i = textEnd;
			}
		}

		return result.join('');
	}

	// Syntax highlight JSON
	function highlightJson(jsonStr: string): string {
		const escaped = escapeHtml(jsonStr);
		return escaped
			.replace(/&quot;([^&]+)&quot;:/g, '<span class="json-key">&quot;$1&quot;</span>:')
			.replace(/: &quot;([^&]*)&quot;/g, ': <span class="json-string">&quot;$1&quot;</span>')
			.replace(/: (\d+)/g, ': <span class="json-number">$1</span>')
			.replace(/: (true|false|null)/g, ': <span class="json-bool">$1</span>');
	}

	// For server mode output, detect if it's XML or JSON and highlight appropriately
	const isOutputXml = $derived(outputXml.trim().startsWith('<'));
</script>

<div class="preview-container">
	<!-- Header with navigation -->
	<div class="preview-header">
		<div class="header-left">
			<span class="preview-title">Preview</span>
			{#if useServerExecution}
				<span class="mode-badge">Live</span>
			{/if}
			{#if showShortcutHint}
				<kbd class="shortcut-hint">4</kbd>
			{/if}
			{#if !useServerExecution}
				<button
					class="search-toggle"
					class:active={showSearch}
					onclick={toggleSearch}
					aria-label={showSearch ? 'Hide search' : 'Search records'}
					title="Search records"
				>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
						<circle cx="11" cy="11" r="8" />
						<path d="M21 21l-4.35-4.35" />
					</svg>
				</button>
			{/if}
			{#if onMaximize}
				<button
					class="maximize-btn"
					onclick={onMaximize}
					aria-label="Open fullscreen preview"
					title="Open fullscreen preview"
				>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
						<path d="M8 3H5a2 2 0 00-2 2v3m18 0V5a2 2 0 00-2-2h-3m0 18h3a2 2 0 002-2v-3M3 16v3a2 2 0 002 2h3" />
					</svg>
				</button>
			{/if}
		</div>
		<div class="record-nav">
			<button
				class="nav-btn"
				onclick={prevRecord}
				disabled={!canGoPrev || serverLoading}
				aria-label="Previous record"
			>
				<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
					<path d="M15 19l-7-7 7-7" />
				</svg>
			</button>
			<span class="record-info">
				{#if serverLoading}
					<span class="loading-indicator">Loading...</span>
				{:else if searchQuery.trim() && !useServerExecution}
					<strong>{filteredPosition + 1}</strong> of <strong>{filteredCount}</strong>
					<span class="filter-note">(filtered)</span>
				{:else if recordId}
					<strong>{currentIndex + 1}</strong> of
					<strong>{totalRecords.toLocaleString()}</strong>
					<span class="record-id" title={recordId}>{recordId}</span>
				{:else}
					Record <strong>{currentIndex + 1}</strong> of
					<strong>{totalRecords.toLocaleString()}</strong>
				{/if}
			</span>
			<button
				class="nav-btn"
				onclick={nextRecord}
				disabled={!canGoNext || serverLoading}
				aria-label="Next record"
			>
				<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
					<path d="M9 5l7 7-7 7" />
				</svg>
			</button>
		</div>
	</div>

	<!-- Search bar (mock mode only) -->
	{#if showSearch && !useServerExecution}
		<div class="search-bar">
			<div class="search-input-wrapper">
				<svg
					class="search-icon"
					viewBox="0 0 24 24"
					fill="none"
					stroke="currentColor"
					stroke-width="2"
				>
					<circle cx="11" cy="11" r="8" />
					<path d="M21 21l-4.35-4.35" />
				</svg>
				<input
					type="text"
					class="search-input"
					placeholder="Search by title, creator, object number..."
					bind:value={searchQuery}
					onkeydown={(e) => {
						if (e.key === 'Enter') {
							e.preventDefault();
							e.shiftKey ? prevRecord() : nextRecord();
						}
					}}
				/>
				{#if searchQuery}
					<button class="clear-btn" onclick={clearSearch} aria-label="Clear search">
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
							<path d="M18 6L6 18M6 6l12 12" />
						</svg>
					</button>
				{/if}
			</div>
			{#if searchQuery.trim()}
				<span class="search-results">
					{filteredCount}
					{filteredCount === 1 ? 'match' : 'matches'}
				</span>
			{/if}
		</div>
	{/if}

	<!-- Error banner -->
	{#if serverError}
		<div class="error-banner">
			<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
				<circle cx="12" cy="12" r="10" />
				<path d="M12 8v4M12 16h.01" />
			</svg>
			<span>{serverError}</span>
		</div>
	{/if}

	<!-- Preview panes -->
	<div class="preview-content">
		<PaneGroup direction="horizontal">
			<!-- Input XML -->
			<Pane defaultSize={50}>
				<div class="pane-wrapper">
					<div class="pane-header">
						<span class="pane-label">Input XML</span>
						<span class="pane-badge">Source</span>
					</div>
					<div class="pane-content">
						{#if serverLoading && !inputXml}
							<div class="loading-placeholder">
								<div class="spinner"></div>
								<span>Loading record...</span>
							</div>
						{:else}
							<pre class="code-block xml">{@html highlightXml(inputXml, searchQuery)}</pre>
						{/if}
					</div>
				</div>
			</Pane>

			<PaneResizer class="pane-resizer" />

			<!-- Output -->
			<Pane defaultSize={50}>
				<div class="pane-wrapper">
					<div class="pane-header">
						<span class="pane-label"
							>Output {useServerExecution ? (isOutputXml ? 'RDF/XML' : 'JSON-LD') : 'EDM/JSON-LD'}</span
						>
						<span class="pane-badge target">Target</span>
					</div>
					<div class="pane-content">
						{#if serverLoading && !outputXml}
							<div class="loading-placeholder">
								<div class="spinner"></div>
								<span>Executing mapping...</span>
							</div>
						{:else if serverError && !outputXml}
							<div class="error-placeholder">
								<span>Mapping execution failed</span>
								<span class="error-hint">Check the error message above</span>
							</div>
						{:else if isOutputXml}
							<pre class="code-block xml">{@html highlightXml(outputXml, '')}</pre>
						{:else}
							<pre class="code-block json">{@html highlightJson(outputXml)}</pre>
						{/if}
					</div>
				</div>
			</Pane>
		</PaneGroup>
	</div>
</div>

<style>
	.preview-container {
		height: 100%;
		display: flex;
		flex-direction: column;
		background: #111827;
	}

	.preview-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 8px 12px;
		background: #1f2937;
		border-bottom: 1px solid #374151;
	}

	.header-left {
		display: flex;
		align-items: center;
		gap: 8px;
	}

	.preview-title {
		font-size: 13px;
		font-weight: 500;
		color: #f3f4f6;
	}

	.mode-badge {
		font-size: 9px;
		text-transform: uppercase;
		padding: 2px 6px;
		border-radius: 4px;
		background: #166534;
		color: #86efac;
		font-weight: 600;
	}

	.shortcut-hint {
		padding: 2px 6px;
		font-size: 10px;
		font-family: ui-monospace, monospace;
		font-weight: bold;
		background: #3b82f6;
		color: white;
		border-radius: 4px;
		animation: pulse 1s ease-in-out infinite;
	}

	@keyframes pulse {
		0%,
		100% {
			opacity: 1;
		}
		50% {
			opacity: 0.7;
		}
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

	.maximize-btn {
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

	.maximize-btn:hover {
		color: #3b82f6;
		background: rgba(59, 130, 246, 0.1);
	}

	.maximize-btn svg {
		width: 14px;
		height: 14px;
	}

	.record-nav {
		display: flex;
		align-items: center;
		gap: 8px;
	}

	.filter-note {
		font-size: 10px;
		color: #6b7280;
		margin-left: 2px;
	}

	.record-id {
		font-size: 10px;
		color: #6b7280;
		margin-left: 4px;
		max-width: 150px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.loading-indicator {
		color: #6b7280;
		font-style: italic;
	}

	/* Search bar */
	.search-bar {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 8px 12px;
		background: #1f2937;
		border-bottom: 1px solid #374151;
	}

	.search-input-wrapper {
		flex: 1;
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
		padding: 6px 30px 6px 32px;
		font-size: 12px;
		border: 1px solid #374151;
		border-radius: 6px;
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
		right: 6px;
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

	.search-results {
		font-size: 11px;
		color: #6b7280;
		white-space: nowrap;
	}

	/* Error banner */
	.error-banner {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 8px 12px;
		background: rgba(239, 68, 68, 0.1);
		border-bottom: 1px solid rgba(239, 68, 68, 0.3);
		color: #fca5a5;
		font-size: 12px;
	}

	.error-banner svg {
		width: 16px;
		height: 16px;
		flex-shrink: 0;
		color: #ef4444;
	}

	.nav-btn {
		width: 28px;
		height: 28px;
		display: flex;
		align-items: center;
		justify-content: center;
		background: #374151;
		border: none;
		border-radius: 6px;
		color: #d1d5db;
		cursor: pointer;
		transition: all 0.15s ease;
	}

	.nav-btn:hover:not(:disabled) {
		background: #4b5563;
		color: #f3f4f6;
	}

	.nav-btn:disabled {
		opacity: 0.4;
		cursor: not-allowed;
	}

	.nav-btn svg {
		width: 16px;
		height: 16px;
	}

	.record-info {
		font-size: 12px;
		color: #9ca3af;
		display: flex;
		align-items: center;
		gap: 4px;
	}

	.record-info strong {
		color: #f3f4f6;
	}

	.preview-content {
		flex: 1;
		min-height: 0;
	}

	.pane-wrapper {
		height: 100%;
		display: flex;
		flex-direction: column;
	}

	.pane-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 6px 12px;
		background: #1f2937;
		border-bottom: 1px solid #374151;
	}

	.pane-label {
		font-size: 11px;
		color: #9ca3af;
		text-transform: uppercase;
		letter-spacing: 0.05em;
	}

	.pane-badge {
		font-size: 9px;
		text-transform: uppercase;
		padding: 2px 6px;
		border-radius: 4px;
		background: #1e40af;
		color: #93c5fd;
	}

	.pane-badge.target {
		background: #166534;
		color: #86efac;
	}

	.pane-content {
		flex: 1;
		overflow: auto;
		padding: 12px;
	}

	.code-block {
		margin: 0;
		font-family: 'JetBrains Mono', 'Fira Code', 'SF Mono', Consolas, monospace;
		font-size: 12px;
		line-height: 1.6;
		color: #e5e7eb;
		white-space: pre-wrap;
		word-break: break-word;
	}

	/* Loading state */
	.loading-placeholder,
	.error-placeholder {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		height: 100%;
		gap: 12px;
		color: #6b7280;
	}

	.error-placeholder {
		color: #fca5a5;
	}

	.error-hint {
		font-size: 11px;
		color: #6b7280;
	}

	.spinner {
		width: 24px;
		height: 24px;
		border: 2px solid #374151;
		border-top-color: #3b82f6;
		border-radius: 50%;
		animation: spin 0.8s linear infinite;
	}

	@keyframes spin {
		to {
			transform: rotate(360deg);
		}
	}

	/* XML syntax highlighting */
	.code-block.xml :global(.xml-tag) {
		color: #60a5fa;
	}

	.code-block.xml :global(.xml-attr) {
		color: #f472b6;
	}

	.code-block.xml :global(.xml-value) {
		color: #4ade80;
	}

	/* Search match highlighting */
	.code-block :global(.search-match) {
		background: rgba(250, 204, 21, 0.4);
		color: inherit;
		padding: 1px 2px;
		border-radius: 2px;
		font-weight: 500;
	}

	/* JSON syntax highlighting */
	.code-block.json :global(.json-key) {
		color: #f472b6;
	}

	.code-block.json :global(.json-string) {
		color: #4ade80;
	}

	.code-block.json :global(.json-number) {
		color: #fbbf24;
	}

	.code-block.json :global(.json-bool) {
		color: #a78bfa;
	}

	/* Pane resizer */
	:global(.pane-resizer) {
		width: 4px;
		background: #374151;
		cursor: col-resize;
		transition: background 0.15s ease;
	}

	:global(.pane-resizer:hover) {
		background: #3b82f6;
	}
</style>
