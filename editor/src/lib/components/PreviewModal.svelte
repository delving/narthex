<script lang="ts">
	import { PaneGroup, Pane, PaneResizer } from 'paneforge';
	import { previewMappingByIndex, previewMappingById, searchRecords, type PreviewMappingResponse, type RecordSearchResult } from '$lib/api/mappingEditor';
	import { sampleRecords, transformRecord, transformRecordWithMappings, recordToXml } from '$lib/sampleData';
	import type { SampleRecord } from '$lib/types';
	import type { Mapping } from '$lib/stores/mappingStore';

	interface Props {
		isOpen: boolean;
		onClose: () => void;
		spec?: string;
		mappings?: Mapping[];
		currentRecordIndex?: number;
		onRecordChange?: (index: number) => void;
		totalRecordCount?: number;
	}

	let {
		isOpen,
		onClose,
		spec,
		mappings = [],
		currentRecordIndex = 0,
		onRecordChange,
		totalRecordCount
	}: Props = $props();

	// Determine if we're using server-side execution
	const useServerExecution = $derived(!!spec);

	// Server-side execution state
	let serverLoading = $state(false);
	let serverError = $state<string | null>(null);
	let serverResult = $state<PreviewMappingResponse | null>(null);

	// Search state
	let showSearch = $state(false);
	let searchMode = $state<'records' | 'id' | 'text'>('records'); // 'records' for searching through all records
	let searchQuery = $state('');
	let searchLoading = $state(false);
	let searchError = $state<string | null>(null);
	let textMatchCount = $state(0);
	let currentTextMatch = $state(0);

	// Record search results
	let searchResults = $state<RecordSearchResult[]>([]);
	let showSearchResults = $state(false);
	let currentSearchResultIndex = $state<number | null>(null); // Track which search result we're viewing

	// Reference to search input for focus
	let searchInputRef = $state<HTMLInputElement | null>(null);

	// Local index if no callback provided
	let localIndex = $state(0);
	const currentIndex = $derived(onRecordChange ? currentRecordIndex : localIndex);

	// Sync local index with prop
	$effect(() => {
		if (isOpen) {
			localIndex = currentRecordIndex;
		}
	});

	// Reset search when modal closes
	$effect(() => {
		if (!isOpen) {
			showSearch = false;
			searchQuery = '';
			searchError = null;
			textMatchCount = 0;
			currentTextMatch = 0;
			searchResults = [];
			showSearchResults = false;
			currentSearchResultIndex = null;
		}
	});

	// Are we viewing a search result?
	const isViewingSearchResult = $derived(currentSearchResultIndex !== null && searchResults.length > 0);
	const canGoPrevResult = $derived(isViewingSearchResult && currentSearchResultIndex! > 0);
	const canGoNextResult = $derived(isViewingSearchResult && currentSearchResultIndex! < searchResults.length - 1);

	// Focus search input when search opens
	$effect(() => {
		if (showSearch && searchInputRef) {
			setTimeout(() => searchInputRef?.focus(), 50);
		}
	});

	// Total records depends on mode
	const totalRecords = $derived(useServerExecution ? (totalRecordCount ?? 0) : sampleRecords.length);

	// Load server preview when modal opens or index changes
	$effect(() => {
		if (isOpen && useServerExecution && spec) {
			loadServerPreview(currentIndex);
		}
	});

	async function loadServerPreview(index: number) {
		serverLoading = true;
		serverError = null;

		try {
			serverResult = await previewMappingByIndex(spec!, index);
			if (!serverResult.success && serverResult.error) {
				serverError = `${serverResult.errorType || 'Error'}: ${serverResult.error}`;
			}
		} catch (err) {
			serverError = err instanceof Error ? err.message : 'Failed to load preview';
			serverResult = null;
		} finally {
			serverLoading = false;
		}
	}

	// Search by record ID
	async function searchByRecordId() {
		if (!searchQuery.trim() || !useServerExecution || !spec) return;

		searchLoading = true;
		searchError = null;

		try {
			const result = await previewMappingById(spec, searchQuery.trim());
			serverResult = result;
			if (!result.success && result.error) {
				searchError = `${result.errorType || 'Error'}: ${result.error}`;
			} else {
				// Successfully found record - we don't know the index, but we have the data
				searchError = null;
			}
		} catch (err) {
			searchError = err instanceof Error ? err.message : 'Record not found';
		} finally {
			searchLoading = false;
		}
	}

	// Search through all records
	async function searchAllRecords() {
		if (!searchQuery.trim() || !useServerExecution || !spec) return;

		searchLoading = true;
		searchError = null;
		searchResults = [];
		showSearchResults = true;
		currentSearchResultIndex = null;

		try {
			const response = await searchRecords(spec, searchQuery.trim(), 50);
			searchResults = response.results;
			if (response.results.length === 0) {
				searchError = 'No records found matching your search';
			} else {
				// Automatically load the first result so paging works immediately
				showSearchResults = false; // Collapse the list
				await loadSearchResult(response.results[0].id, 0);
			}
		} catch (err) {
			searchError = err instanceof Error ? err.message : 'Search failed';
			searchResults = [];
		} finally {
			searchLoading = false;
		}
	}

	// Load a specific record from search results
	async function loadSearchResult(recordId: string, resultIndex?: number) {
		if (!spec) return;

		serverLoading = true;
		serverError = null;

		try {
			const result = await previewMappingById(spec, recordId);
			serverResult = result;
			if (!result.success && result.error) {
				serverError = `${result.errorType || 'Error'}: ${result.error}`;
			}
			// Track which search result we're viewing
			if (resultIndex !== undefined) {
				currentSearchResultIndex = resultIndex;
			} else {
				// Find index by ID
				const idx = searchResults.findIndex(r => r.id === recordId);
				currentSearchResultIndex = idx >= 0 ? idx : null;
			}
			// Keep search results visible but collapse the list
			showSearchResults = false;
		} catch (err) {
			serverError = err instanceof Error ? err.message : 'Failed to load record';
		} finally {
			serverLoading = false;
		}
	}

	// Navigate through search results
	function prevSearchResult() {
		if (canGoPrevResult && currentSearchResultIndex !== null) {
			const newIndex = currentSearchResultIndex - 1;
			loadSearchResult(searchResults[newIndex].id, newIndex);
		}
	}

	function nextSearchResult() {
		if (canGoNextResult && currentSearchResultIndex !== null) {
			const newIndex = currentSearchResultIndex + 1;
			loadSearchResult(searchResults[newIndex].id, newIndex);
		}
	}

	// Exit search result mode and return to normal navigation
	function exitSearchResultMode() {
		currentSearchResultIndex = null;
		searchResults = [];
		searchQuery = '';
		showSearch = false;
		// Reload current record by index
		if (useServerExecution && spec) {
			loadServerPreview(currentIndex);
		}
	}

	// Count text matches in content
	function countMatches(text: string, query: string): number {
		if (!query.trim()) return 0;
		const regex = new RegExp(escapeRegex(query), 'gi');
		const matches = text.match(regex);
		return matches ? matches.length : 0;
	}

	// Update match count when search query or content changes
	$effect(() => {
		if (searchMode === 'text' && searchQuery.trim()) {
			const inputMatches = countMatches(inputXml, searchQuery);
			const outputMatches = countMatches(outputXml, searchQuery);
			textMatchCount = inputMatches + outputMatches;
			currentTextMatch = textMatchCount > 0 ? 1 : 0;
		} else {
			textMatchCount = 0;
			currentTextMatch = 0;
		}
	});

	// Mock mode: current record
	const currentRecord = $derived(!useServerExecution ? sampleRecords[currentIndex] : null);

	// Generated XML
	const inputXml = $derived.by(() => {
		if (useServerExecution) {
			return serverResult?.inputXml || '';
		}
		return currentRecord ? recordToXml(currentRecord) : '';
	});

	// Transformed output
	const outputXml = $derived.by(() => {
		if (useServerExecution) {
			return serverResult?.outputXml || '';
		}
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
		if (currentIndex > 0) {
			const newIndex = currentIndex - 1;
			if (onRecordChange) {
				onRecordChange(newIndex);
			} else {
				localIndex = newIndex;
			}
		}
	}

	function nextRecord() {
		if (currentIndex < totalRecords - 1) {
			const newIndex = currentIndex + 1;
			if (onRecordChange) {
				onRecordChange(newIndex);
			} else {
				localIndex = newIndex;
			}
		}
	}

	const canGoPrev = $derived(currentIndex > 0);
	const canGoNext = $derived(currentIndex < totalRecords - 1);

	// Toggle search
	function toggleSearch() {
		showSearch = !showSearch;
		if (!showSearch) {
			searchQuery = '';
			searchError = null;
			textMatchCount = 0;
		}
	}

	// Handle search submission
	function handleSearchSubmit() {
		if (searchMode === 'records') {
			searchAllRecords();
		} else if (searchMode === 'id') {
			searchByRecordId();
		}
		// Text search is automatic via highlighting
	}

	// Handle keyboard navigation
	function handleKeydown(e: KeyboardEvent) {
		if (!isOpen) return;

		if (e.key === 'Escape') {
			if (showSearchResults) {
				e.preventDefault();
				showSearchResults = false;
			} else if (isViewingSearchResult) {
				e.preventDefault();
				exitSearchResultMode();
			} else if (showSearch) {
				e.preventDefault();
				toggleSearch();
			} else {
				e.preventDefault();
				onClose();
			}
		} else if (e.key === 'ArrowLeft' || e.key === '[') {
			if (!showSearch || document.activeElement !== searchInputRef) {
				e.preventDefault();
				if (isViewingSearchResult) {
					prevSearchResult();
				} else {
					prevRecord();
				}
			}
		} else if (e.key === 'ArrowRight' || e.key === ']') {
			if (!showSearch || document.activeElement !== searchInputRef) {
				e.preventDefault();
				if (isViewingSearchResult) {
					nextSearchResult();
				} else {
					nextRecord();
				}
			}
		} else if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
			e.preventDefault();
			if (!showSearch) {
				showSearch = true;
				searchMode = 'text';
			} else {
				searchMode = 'text';
				searchInputRef?.focus();
			}
		} else if ((e.ctrlKey || e.metaKey) && e.key === 'g') {
			e.preventDefault();
			if (!showSearch) {
				showSearch = true;
				searchMode = 'id';
			} else {
				searchMode = 'id';
				searchInputRef?.focus();
			}
		} else if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key === 'F') {
			// Ctrl+Shift+F for record search
			e.preventDefault();
			if (!showSearch) {
				showSearch = true;
				searchMode = 'records';
			} else {
				searchMode = 'records';
				searchInputRef?.focus();
			}
		} else if (e.key === 'Enter' && isViewingSearchResult && document.activeElement !== searchInputRef) {
			// Enter to go to next search result
			e.preventDefault();
			if (e.shiftKey) {
				prevSearchResult();
			} else {
				nextSearchResult();
			}
		}
	}

	// Escape special regex characters
	function escapeRegex(str: string): string {
		return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
	}

	// Escape HTML entities
	function escapeHtml(str: string): string {
		return str
			.replace(/&/g, '&amp;')
			.replace(/</g, '&lt;')
			.replace(/>/g, '&gt;')
			.replace(/"/g, '&quot;');
	}

	// Highlight search matches
	function highlightMatches(text: string, query: string): string {
		if (!query.trim()) return text;
		const escapedQuery = escapeRegex(query);
		const regex = new RegExp(`(${escapedQuery})`, 'gi');
		return text.replace(regex, '<mark class="search-highlight">$1</mark>');
	}

	// Highlight snippet with search match (for search results)
	function highlightSnippet(snippet: string, query: string): string {
		const escaped = escapeHtml(snippet);
		if (!query.trim()) return escaped;
		const escapedQuery = escapeRegex(query);
		const regex = new RegExp(`(${escapedQuery})`, 'gi');
		return escaped.replace(regex, '<mark class="snippet-highlight">$1</mark>');
	}

	// Syntax highlight XML with optional search highlighting
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

				// Highlight tag name if it matches search
				let escapedTagName = escapeHtml(tagName);
				if (searchQuery && searchMode === 'text') {
					escapedTagName = highlightMatches(escapedTagName, searchQuery);
				}
				highlighted += `<span class="xml-tag">${escapedTagName}</span>`;

				if (attrs) {
					attrs = attrs.replace(
						/(\s+)([\w\-.:]+)(=)("([^"]*)")/g,
						(_, space, name, eq, fullValue) => {
							let escapedValue = escapeHtml(fullValue);
							if (searchQuery && searchMode === 'text') {
								escapedValue = highlightMatches(escapedValue, searchQuery);
							}
							return `${space}<span class="xml-attr">${escapeHtml(name)}</span>=<span class="xml-value">${escapedValue}</span>`;
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
				let escapedText = escapeHtml(text);
				if (searchQuery && searchMode === 'text') {
					escapedText = highlightMatches(escapedText, searchQuery);
				}
				result.push(escapedText);
				i = textEnd;
			}
		}

		return result.join('');
	}

	// Syntax highlight JSON with optional search highlighting
	function highlightJson(jsonStr: string, searchQuery: string = ''): string {
		let escaped = escapeHtml(jsonStr);

		// Apply search highlighting first if in text mode
		if (searchQuery && searchMode === 'text') {
			escaped = highlightMatches(escaped, searchQuery);
		}

		return escaped
			.replace(/&quot;([^&]+)&quot;:/g, '<span class="json-key">&quot;$1&quot;</span>:')
			.replace(/: &quot;([^&]*)&quot;/g, ': <span class="json-string">&quot;$1&quot;</span>')
			.replace(/: (\d+)/g, ': <span class="json-number">$1</span>')
			.replace(/: (true|false|null)/g, ': <span class="json-bool">$1</span>');
	}

	const isOutputXml = $derived(outputXml.trim().startsWith('<'));
</script>

<svelte:window onkeydown={handleKeydown} />

{#if isOpen}
	<!-- Backdrop -->
	<div
		class="modal-backdrop"
		onclick={onClose}
		onkeydown={(e) => e.key === 'Enter' && onClose()}
		role="button"
		tabindex="-1"
		aria-label="Close modal"
	></div>

	<!-- Modal -->
	<div class="modal-container" role="dialog" aria-modal="true" aria-label="Preview">
		<!-- Header -->
		<div class="modal-header">
			<div class="header-left">
				<h2 class="modal-title">Record Preview</h2>
				{#if useServerExecution}
					<span class="mode-badge">Live Execution</span>
				{/if}
				{#if recordId}
					<span class="record-id" title={recordId}>ID: {recordId}</span>
				{/if}
			</div>
			<div class="header-center">
				{#if isViewingSearchResult}
					<!-- Search result navigation -->
					<button
						class="nav-btn"
						onclick={prevSearchResult}
						disabled={!canGoPrevResult || serverLoading}
						aria-label="Previous search result"
						title="Previous result (← or [)"
					>
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
							<path d="M15 19l-7-7 7-7" />
						</svg>
					</button>
					<span class="record-info search-result-info">
						{#if serverLoading}
							<span class="loading-text">Loading...</span>
						{:else}
							<span class="result-label">Result</span>
							<strong>{(currentSearchResultIndex ?? 0) + 1}</strong>
							<span class="separator">/</span>
							<strong>{searchResults.length}</strong>
						{/if}
					</span>
					<button
						class="nav-btn"
						onclick={nextSearchResult}
						disabled={!canGoNextResult || serverLoading}
						aria-label="Next search result"
						title="Next result (→ or ])"
					>
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
							<path d="M9 5l7 7-7 7" />
						</svg>
					</button>
					<button
						class="exit-search-btn"
						onclick={exitSearchResultMode}
						title="Exit search results (Esc)"
					>
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
							<path d="M18 6L6 18M6 6l12 12" />
						</svg>
						<span>Exit Search</span>
					</button>
				{:else}
					<!-- Normal record navigation -->
					<button
						class="nav-btn"
						onclick={prevRecord}
						disabled={!canGoPrev || serverLoading}
						aria-label="Previous record"
						title="Previous record (← or [)"
					>
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
							<path d="M15 19l-7-7 7-7" />
						</svg>
					</button>
					<span class="record-info">
						{#if serverLoading || searchLoading}
							<span class="loading-text">Loading...</span>
						{:else}
							<strong>{currentIndex + 1}</strong>
							<span class="separator">/</span>
							<strong>{totalRecords.toLocaleString()}</strong>
						{/if}
					</span>
					<button
						class="nav-btn"
						onclick={nextRecord}
						disabled={!canGoNext || serverLoading}
						aria-label="Next record"
						title="Next record (→ or ])"
					>
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
							<path d="M9 5l7 7-7 7" />
						</svg>
					</button>
				{/if}
			</div>
			<div class="header-right">
				<button
					class="search-btn"
					class:active={showSearch}
					onclick={toggleSearch}
					title="Search (Ctrl+Shift+F records, Ctrl+F text, Ctrl+G ID)"
				>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
						<circle cx="11" cy="11" r="8" />
						<path d="M21 21l-4.35-4.35" />
					</svg>
				</button>
				<span class="keyboard-hint">
					<kbd>←</kbd> <kbd>→</kbd> navigate
					<kbd>Esc</kbd> close
				</span>
				<button class="close-btn" onclick={onClose} aria-label="Close modal">
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
						<path d="M18 6L6 18M6 6l12 12" />
					</svg>
				</button>
			</div>
		</div>

		<!-- Search bar -->
		{#if showSearch}
			<div class="search-bar">
				<div class="search-mode-tabs">
					{#if useServerExecution}
						<button
							class="mode-tab"
							class:active={searchMode === 'records'}
							onclick={() => { searchMode = 'records'; showSearchResults = searchResults.length > 0; }}
							title="Search through all records (Ctrl+Shift+F)"
						>
							Search Records
						</button>
					{/if}
					<button
						class="mode-tab"
						class:active={searchMode === 'text'}
						onclick={() => { searchMode = 'text'; showSearchResults = false; }}
						title="Highlight text in current record (Ctrl+F)"
					>
						Highlight
					</button>
					{#if useServerExecution}
						<button
							class="mode-tab"
							class:active={searchMode === 'id'}
							onclick={() => { searchMode = 'id'; showSearchResults = false; }}
							title="Find record by ID (Ctrl+G)"
						>
							Find by ID
						</button>
					{/if}
				</div>
				<div class="search-input-wrapper">
					<svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
						<circle cx="11" cy="11" r="8" />
						<path d="M21 21l-4.35-4.35" />
					</svg>
					<input
						bind:this={searchInputRef}
						type="text"
						class="search-input"
						placeholder={searchMode === 'records' ? 'Search all records...' : searchMode === 'id' ? 'Enter record ID...' : 'Highlight in current record...'}
						bind:value={searchQuery}
						onkeydown={(e) => {
							if (e.key === 'Enter') {
								e.preventDefault();
								handleSearchSubmit();
							}
						}}
					/>
					{#if searchQuery}
						<button
							class="clear-btn"
							onclick={() => { searchQuery = ''; searchError = null; searchResults = []; showSearchResults = false; }}
							aria-label="Clear search"
						>
							<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
								<path d="M18 6L6 18M6 6l12 12" />
							</svg>
						</button>
					{/if}
				</div>
				{#if (searchMode === 'records' || searchMode === 'id') && useServerExecution}
					<button
						class="go-btn"
						onclick={handleSearchSubmit}
						disabled={!searchQuery.trim() || searchLoading}
					>
						{searchLoading ? 'Searching...' : searchMode === 'records' ? 'Search' : 'Go'}
					</button>
				{/if}
				{#if searchMode === 'text' && searchQuery.trim()}
					<span class="match-count">
						{textMatchCount} {textMatchCount === 1 ? 'match' : 'matches'}
					</span>
				{/if}
				{#if searchMode === 'records' && searchResults.length > 0 && !searchLoading}
					<button
						class="results-toggle"
						onclick={() => showSearchResults = !showSearchResults}
					>
						{searchResults.length} {searchResults.length === 1 ? 'result' : 'results'}
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class:rotated={showSearchResults}>
							<path d="M6 9l6 6 6-6" />
						</svg>
					</button>
				{/if}
				{#if searchError}
					<span class="search-error">{searchError}</span>
				{/if}
			</div>

			<!-- Search results list -->
			{#if showSearchResults && searchResults.length > 0}
				<div class="search-results">
					<div class="results-header">
						<span>Found {searchResults.length} matching records</span>
						<button class="close-results" onclick={() => showSearchResults = false}>
							<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
								<path d="M18 6L6 18M6 6l12 12" />
							</svg>
						</button>
					</div>
					<div class="results-list">
						{#each searchResults as result, index (result.id)}
							<button
								class="result-item"
								class:active={currentSearchResultIndex === index}
								onclick={() => loadSearchResult(result.id, index)}
							>
								<span class="result-id">{result.id}</span>
								<span class="result-snippet">{@html highlightSnippet(result.snippet, searchQuery)}</span>
							</button>
						{/each}
					</div>
				</div>
			{/if}
		{/if}

		<!-- Error banner -->
		{#if serverError && !searchError}
			<div class="error-banner">
				<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
					<circle cx="12" cy="12" r="10" />
					<path d="M12 8v4M12 16h.01" />
				</svg>
				<span>{serverError}</span>
			</div>
		{/if}

		<!-- Content -->
		<div class="modal-content">
			<PaneGroup direction="horizontal">
				<!-- Input XML -->
				<Pane defaultSize={50}>
					<div class="pane-wrapper">
						<div class="pane-header">
							<span class="pane-label">Input XML</span>
							<span class="pane-badge source">Source Record</span>
						</div>
						<div class="pane-content">
							{#if (serverLoading || searchLoading) && !inputXml}
								<div class="loading-placeholder">
									<div class="spinner"></div>
									<span>Loading source record...</span>
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
							<span class="pane-label">
								Output {isOutputXml ? 'RDF/XML' : 'JSON-LD'}
							</span>
							<span class="pane-badge target">Transformed</span>
						</div>
						<div class="pane-content">
							{#if (serverLoading || searchLoading) && !outputXml}
								<div class="loading-placeholder">
									<div class="spinner"></div>
									<span>Executing mapping...</span>
								</div>
							{:else if serverError && !outputXml}
								<div class="error-placeholder">
									<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
										<circle cx="12" cy="12" r="10" />
										<path d="M12 8v4M12 16h.01" />
									</svg>
									<span>Mapping execution failed</span>
									<span class="error-hint">See error message above</span>
								</div>
							{:else if isOutputXml}
								<pre class="code-block xml">{@html highlightXml(outputXml, searchQuery)}</pre>
							{:else}
								<pre class="code-block json">{@html highlightJson(outputXml, searchQuery)}</pre>
							{/if}
						</div>
					</div>
				</Pane>
			</PaneGroup>
		</div>
	</div>
{/if}

<style>
	.modal-backdrop {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.8);
		z-index: 1000;
		backdrop-filter: blur(4px);
	}

	.modal-container {
		position: fixed;
		inset: 24px;
		background: #111827;
		border-radius: 12px;
		border: 1px solid #374151;
		z-index: 1001;
		display: flex;
		flex-direction: column;
		box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5);
	}

	.modal-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 16px 20px;
		background: #1f2937;
		border-bottom: 1px solid #374151;
		border-radius: 12px 12px 0 0;
	}

	.header-left {
		display: flex;
		align-items: center;
		gap: 12px;
	}

	.modal-title {
		font-size: 16px;
		font-weight: 600;
		color: #f3f4f6;
		margin: 0;
	}

	.mode-badge {
		font-size: 10px;
		text-transform: uppercase;
		padding: 3px 8px;
		border-radius: 4px;
		background: #166534;
		color: #86efac;
		font-weight: 600;
	}

	.record-id {
		font-size: 12px;
		color: #6b7280;
		max-width: 200px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.header-center {
		display: flex;
		align-items: center;
		gap: 12px;
	}

	.nav-btn {
		width: 36px;
		height: 36px;
		display: flex;
		align-items: center;
		justify-content: center;
		background: #374151;
		border: none;
		border-radius: 8px;
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
		width: 20px;
		height: 20px;
	}

	.record-info {
		font-size: 14px;
		color: #9ca3af;
		display: flex;
		align-items: center;
		gap: 4px;
		min-width: 100px;
		justify-content: center;
	}

	.record-info strong {
		color: #f3f4f6;
		font-size: 16px;
	}

	.separator {
		color: #6b7280;
	}

	.loading-text {
		color: #6b7280;
		font-style: italic;
	}

	/* Search result navigation styling */
	.search-result-info {
		background: rgba(59, 130, 246, 0.1);
		padding: 4px 12px;
		border-radius: 6px;
		border: 1px solid rgba(59, 130, 246, 0.3);
	}

	.search-result-info .result-label {
		font-size: 11px;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: #3b82f6;
		margin-right: 4px;
	}

	.exit-search-btn {
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 6px 12px;
		margin-left: 8px;
		font-size: 12px;
		background: rgba(239, 68, 68, 0.1);
		border: 1px solid rgba(239, 68, 68, 0.3);
		border-radius: 6px;
		color: #fca5a5;
		cursor: pointer;
		transition: all 0.15s;
	}

	.exit-search-btn:hover {
		background: rgba(239, 68, 68, 0.2);
		color: #fecaca;
	}

	.exit-search-btn svg {
		width: 14px;
		height: 14px;
	}

	.header-right {
		display: flex;
		align-items: center;
		gap: 12px;
	}

	.search-btn {
		width: 36px;
		height: 36px;
		display: flex;
		align-items: center;
		justify-content: center;
		background: transparent;
		border: none;
		border-radius: 8px;
		color: #9ca3af;
		cursor: pointer;
		transition: all 0.15s ease;
	}

	.search-btn:hover {
		background: #374151;
		color: #f3f4f6;
	}

	.search-btn.active {
		background: rgba(59, 130, 246, 0.2);
		color: #3b82f6;
	}

	.search-btn svg {
		width: 18px;
		height: 18px;
	}

	.keyboard-hint {
		font-size: 11px;
		color: #6b7280;
		display: flex;
		align-items: center;
		gap: 6px;
	}

	.keyboard-hint kbd {
		padding: 2px 6px;
		font-size: 10px;
		font-family: ui-monospace, monospace;
		background: #374151;
		border-radius: 4px;
		color: #9ca3af;
	}

	.close-btn {
		width: 36px;
		height: 36px;
		display: flex;
		align-items: center;
		justify-content: center;
		background: transparent;
		border: none;
		border-radius: 8px;
		color: #9ca3af;
		cursor: pointer;
		transition: all 0.15s ease;
	}

	.close-btn:hover {
		background: #374151;
		color: #f3f4f6;
	}

	.close-btn svg {
		width: 20px;
		height: 20px;
	}

	/* Search bar */
	.search-bar {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 12px 20px;
		background: #1f2937;
		border-bottom: 1px solid #374151;
	}

	.search-mode-tabs {
		display: flex;
		gap: 4px;
		background: #111827;
		padding: 4px;
		border-radius: 8px;
	}

	.mode-tab {
		padding: 6px 12px;
		font-size: 12px;
		background: transparent;
		border: none;
		border-radius: 6px;
		color: #9ca3af;
		cursor: pointer;
		transition: all 0.15s;
	}

	.mode-tab:hover {
		color: #f3f4f6;
	}

	.mode-tab.active {
		background: #374151;
		color: #f3f4f6;
	}

	.search-input-wrapper {
		flex: 1;
		position: relative;
		display: flex;
		align-items: center;
		max-width: 400px;
	}

	.search-icon {
		position: absolute;
		left: 12px;
		width: 16px;
		height: 16px;
		color: #6b7280;
		pointer-events: none;
	}

	.search-input {
		width: 100%;
		padding: 8px 36px 8px 38px;
		font-size: 13px;
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
		right: 8px;
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

	.go-btn {
		padding: 8px 16px;
		font-size: 13px;
		font-weight: 500;
		background: #3b82f6;
		border: none;
		border-radius: 8px;
		color: white;
		cursor: pointer;
		transition: all 0.15s;
	}

	.go-btn:hover:not(:disabled) {
		background: #2563eb;
	}

	.go-btn:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	.match-count {
		font-size: 12px;
		color: #6b7280;
		white-space: nowrap;
	}

	.search-error {
		font-size: 12px;
		color: #fca5a5;
		white-space: nowrap;
	}

	/* Results toggle button */
	.results-toggle {
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 6px 12px;
		font-size: 12px;
		background: #374151;
		border: none;
		border-radius: 6px;
		color: #d1d5db;
		cursor: pointer;
		transition: all 0.15s;
	}

	.results-toggle:hover {
		background: #4b5563;
		color: #f3f4f6;
	}

	.results-toggle svg {
		width: 14px;
		height: 14px;
		transition: transform 0.2s;
	}

	.results-toggle svg.rotated {
		transform: rotate(180deg);
	}

	/* Search results */
	.search-results {
		background: #1f2937;
		border-bottom: 1px solid #374151;
		max-height: 300px;
		display: flex;
		flex-direction: column;
	}

	.results-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 8px 16px;
		background: #111827;
		font-size: 12px;
		color: #9ca3af;
		border-bottom: 1px solid #374151;
	}

	.close-results {
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

	.close-results:hover {
		color: #9ca3af;
		background: #374151;
	}

	.close-results svg {
		width: 14px;
		height: 14px;
	}

	.results-list {
		overflow-y: auto;
		flex: 1;
	}

	.result-item {
		display: flex;
		flex-direction: column;
		gap: 4px;
		width: 100%;
		padding: 10px 16px;
		text-align: left;
		background: transparent;
		border: none;
		border-bottom: 1px solid #374151;
		color: #e5e7eb;
		cursor: pointer;
		transition: background 0.15s;
	}

	.result-item:hover {
		background: rgba(59, 130, 246, 0.1);
	}

	.result-item.active {
		background: rgba(59, 130, 246, 0.2);
		border-left: 3px solid #3b82f6;
	}

	.result-item:last-child {
		border-bottom: none;
	}

	.result-id {
		font-size: 12px;
		font-weight: 500;
		color: #3b82f6;
		font-family: 'JetBrains Mono', 'Fira Code', monospace;
	}

	.result-snippet {
		font-size: 12px;
		color: #9ca3af;
		line-height: 1.4;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-family: 'JetBrains Mono', 'Fira Code', monospace;
	}

	.result-snippet :global(.snippet-highlight) {
		background: rgba(250, 204, 21, 0.4);
		color: #fef08a;
		padding: 1px 2px;
		border-radius: 2px;
		font-weight: 500;
	}

	/* Error banner */
	.error-banner {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 12px 20px;
		background: rgba(239, 68, 68, 0.1);
		border-bottom: 1px solid rgba(239, 68, 68, 0.3);
		color: #fca5a5;
		font-size: 13px;
	}

	.error-banner svg {
		width: 18px;
		height: 18px;
		flex-shrink: 0;
		color: #ef4444;
	}

	.modal-content {
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
		padding: 10px 16px;
		background: #1f2937;
		border-bottom: 1px solid #374151;
	}

	.pane-label {
		font-size: 12px;
		color: #9ca3af;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		font-weight: 500;
	}

	.pane-badge {
		font-size: 10px;
		text-transform: uppercase;
		padding: 3px 8px;
		border-radius: 4px;
		font-weight: 500;
	}

	.pane-badge.source {
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
		padding: 16px;
	}

	.code-block {
		margin: 0;
		font-family: 'JetBrains Mono', 'Fira Code', 'SF Mono', Consolas, monospace;
		font-size: 13px;
		line-height: 1.7;
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
		gap: 16px;
		color: #6b7280;
		font-size: 14px;
	}

	.error-placeholder {
		color: #fca5a5;
	}

	.error-placeholder svg {
		width: 32px;
		height: 32px;
		color: #ef4444;
	}

	.error-hint {
		font-size: 12px;
		color: #6b7280;
	}

	.spinner {
		width: 32px;
		height: 32px;
		border: 3px solid #374151;
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

	/* Search highlight */
	.code-block :global(.search-highlight) {
		background: rgba(250, 204, 21, 0.4);
		color: inherit;
		padding: 1px 2px;
		border-radius: 2px;
		font-weight: 500;
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
