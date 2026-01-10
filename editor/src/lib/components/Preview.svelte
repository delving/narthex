<script lang="ts">
	import { PaneGroup, Pane, PaneResizer } from 'paneforge';
	import { sampleRecords, transformRecord, transformRecordWithMappings, recordToXml } from '$lib/sampleData';
	import type { SampleRecord, OutputRecord } from '$lib/types';
	import type { Mapping } from '$lib/stores/mappingStore';

	interface Props {
		groovyCode?: string;
		mappings?: Mapping[];
		currentRecordIndex?: number;
		onRecordChange?: (index: number) => void;
		showShortcutHint?: boolean;
	}

	let { groovyCode, mappings = [], currentRecordIndex = 0, onRecordChange, showShortcutHint = false }: Props = $props();

	// Search state
	let searchQuery = $state('');
	let showSearch = $state(false);

	// Check if a record matches the search query
	function recordMatchesSearch(record: SampleRecord, query: string): boolean {
		if (!query.trim()) return true;
		const lowerQuery = query.toLowerCase();

		// Search through all string values in the record
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

	// Get indices of matching records
	const matchingIndices = $derived.by(() => {
		if (!searchQuery.trim()) {
			return sampleRecords.map((_, i) => i);
		}
		return sampleRecords
			.map((record, index) => recordMatchesSearch(record, searchQuery) ? index : -1)
			.filter((i) => i !== -1);
	});

	// Current position in filtered results (index within matchingIndices)
	let filteredPosition = $state(0);

	// Jump to first matching record when search query changes
	$effect(() => {
		if (searchQuery.trim() && matchingIndices.length > 0) {
			// Jump to the first matching record
			const firstMatchIndex = matchingIndices[0];
			if (onRecordChange) {
				onRecordChange(firstMatchIndex);
			} else {
				localIndex = firstMatchIndex;
			}
			filteredPosition = 0;
		}
	});

	// Track which position in filtered results the current record is at
	$effect(() => {
		if (searchQuery.trim() && matchingIndices.length > 0) {
			const currentIdx = onRecordChange ? currentRecordIndex : localIndex;
			const pos = matchingIndices.indexOf(currentIdx);
			if (pos !== -1) {
				filteredPosition = pos;
			}
		}
	});

	// Use prop if provided, otherwise local state
	let localIndex = $state(0);
	const currentIndex = $derived(onRecordChange ? currentRecordIndex : localIndex);

	// Total records (filtered or all)
	const totalRecords = sampleRecords.length;
	const filteredCount = $derived(matchingIndices.length);

	// Current record
	const currentRecord = $derived(sampleRecords[currentIndex]);

	// Generated XML
	const inputXml = $derived(recordToXml(currentRecord));

	// Transformed output - use mappings if available, otherwise fall back to default
	const outputJson = $derived(
		mappings.length > 0
			? transformRecordWithMappings(currentRecord, mappings)
			: transformRecord(currentRecord)
	);

	// Navigation - when searching, navigate through filtered results
	function prevRecord() {
		if (searchQuery.trim() && matchingIndices.length > 0) {
			// Navigate within filtered results
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
			// Normal navigation
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
		if (searchQuery.trim() && matchingIndices.length > 0) {
			// Navigate within filtered results
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
			// Normal navigation
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
		if (searchQuery.trim()) return filteredPosition > 0;
		return currentIndex > 0;
	});

	const canGoNext = $derived.by(() => {
		if (searchQuery.trim()) return filteredPosition < matchingIndices.length - 1;
		return currentIndex < totalRecords - 1;
	});

	// Toggle search visibility
	function toggleSearch() {
		showSearch = !showSearch;
		if (!showSearch) {
			searchQuery = '';
		}
	}

	// Clear search
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
			// Add text before match
			if (index > lastIndex) {
				result.push(text.slice(lastIndex, index));
			}
			// Add highlighted match
			result.push(`<mark class="search-match">${text.slice(index, index + query.length)}</mark>`);
			lastIndex = index + query.length;
			index = lowerText.indexOf(lowerQuery, lastIndex);
		}

		// Add remaining text
		if (lastIndex < text.length) {
			result.push(text.slice(lastIndex));
		}

		return result.join('');
	}

	// Syntax highlight XML using token-based approach
	function highlightXml(xml: string, searchQuery: string = ''): string {
		// Match XML structure: tags, attributes, text content
		const result: string[] = [];
		let i = 0;

		while (i < xml.length) {
			if (xml[i] === '<') {
				// Find the end of the tag
				const tagEnd = xml.indexOf('>', i);
				if (tagEnd === -1) break;

				const tagContent = xml.slice(i + 1, tagEnd);
				const isClosing = tagContent.startsWith('/');
				const isSelfClosing = tagContent.endsWith('/');

				// Parse tag name and attributes
				const tagStr = isClosing ? tagContent.slice(1) : tagContent;
				const cleanTag = isSelfClosing ? tagStr.slice(0, -1).trim() : tagStr;

				// Split into tag name and attributes
				const spaceIdx = cleanTag.indexOf(' ');
				let tagName: string;
				let attrs = '';

				if (spaceIdx > -1) {
					tagName = cleanTag.slice(0, spaceIdx);
					attrs = cleanTag.slice(spaceIdx);
				} else {
					tagName = cleanTag;
				}

				// Build highlighted tag
				let highlighted = '&lt;';
				if (isClosing) highlighted += '/';
				highlighted += `<span class="xml-tag">${escapeHtml(tagName)}</span>`;

				// Highlight attributes (and search matches in attribute values)
				if (attrs) {
					attrs = attrs.replace(/(\s+)([\w\-.:]+)(=)("([^"]*)")/g, (_, space, name, eq, fullValue, innerValue) => {
						const escapedValue = escapeHtml(fullValue);
						const highlightedValue = searchQuery ? highlightSearchMatches(escapedValue, searchQuery) : escapedValue;
						return `${space}<span class="xml-attr">${escapeHtml(name)}</span>=<span class="xml-value">${highlightedValue}</span>`;
					});
					highlighted += attrs;
				}

				if (isSelfClosing && !isClosing) highlighted += ' /';
				highlighted += '&gt;';

				result.push(highlighted);
				i = tagEnd + 1;
			} else {
				// Text content - find next tag
				const nextTag = xml.indexOf('<', i);
				const textEnd = nextTag === -1 ? xml.length : nextTag;
				const text = xml.slice(i, textEnd);
				const escapedText = escapeHtml(text);
				// Highlight search matches in text content
				const highlightedText = searchQuery ? highlightSearchMatches(escapedText, searchQuery) : escapedText;
				result.push(highlightedText);
				i = textEnd;
			}
		}

		return result.join('');
	}

	// Syntax highlight JSON
	function highlightJson(obj: OutputRecord): string {
		const json = JSON.stringify(obj, null, 2);
		// Escape HTML first, then apply highlighting
		const escaped = escapeHtml(json);
		return escaped
			.replace(/&quot;([^&]+)&quot;:/g, '<span class="json-key">&quot;$1&quot;</span>:')
			.replace(/: &quot;([^&]*)&quot;/g, ': <span class="json-string">&quot;$1&quot;</span>')
			.replace(/: (\d+)/g, ': <span class="json-number">$1</span>')
			.replace(/: (true|false|null)/g, ': <span class="json-bool">$1</span>');
	}
</script>

<div class="preview-container">
	<!-- Header with navigation -->
	<div class="preview-header">
		<div class="header-left">
			<span class="preview-title">Preview</span>
			{#if showShortcutHint}
				<kbd class="shortcut-hint">4</kbd>
			{/if}
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
		</div>
		<div class="record-nav">
			<button
				class="nav-btn"
				onclick={prevRecord}
				disabled={!canGoPrev}
				aria-label="Previous record"
			>
				<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
					<path d="M15 19l-7-7 7-7" />
				</svg>
			</button>
			<span class="record-info">
				{#if searchQuery.trim()}
					<strong>{filteredPosition + 1}</strong> of <strong>{filteredCount}</strong>
					<span class="filter-note">(filtered)</span>
				{:else}
					Record <strong>{currentIndex + 1}</strong> of <strong>{totalRecords.toLocaleString()}</strong>
				{/if}
			</span>
			<button
				class="nav-btn"
				onclick={nextRecord}
				disabled={!canGoNext}
				aria-label="Next record"
			>
				<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
					<path d="M9 5l7 7-7 7" />
				</svg>
			</button>
		</div>
	</div>

	<!-- Search bar (shown when toggled) -->
	{#if showSearch}
		<div class="search-bar">
			<div class="search-input-wrapper">
				<svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
					<circle cx="11" cy="11" r="8" />
					<path d="M21 21l-4.35-4.35" />
				</svg>
				<input
					type="text"
					class="search-input"
					placeholder="Search by title, creator, object number..."
					bind:value={searchQuery}
					onkeydown={(e) => { if (e.key === 'Enter') { e.preventDefault(); e.shiftKey ? prevRecord() : nextRecord(); } }}
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
					{filteredCount} {filteredCount === 1 ? 'match' : 'matches'}
				</span>
			{/if}
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
						<pre class="code-block xml">{@html highlightXml(inputXml, searchQuery)}</pre>
					</div>
				</div>
			</Pane>

			<PaneResizer class="pane-resizer" />

			<!-- Output JSON-LD -->
			<Pane defaultSize={50}>
				<div class="pane-wrapper">
					<div class="pane-header">
						<span class="pane-label">Output EDM/JSON-LD</span>
						<span class="pane-badge target">Target</span>
					</div>
					<div class="pane-content">
						<pre class="code-block json">{@html highlightJson(outputJson)}</pre>
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
		0%, 100% { opacity: 1; }
		50% { opacity: 0.7; }
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
