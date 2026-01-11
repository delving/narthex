<script lang="ts">
	import { mappingsStore, type Mapping } from '$lib/stores/mappingStore';
	import { editedCodeStore, modifiedPaths } from '$lib/stores/editedCodeStore';
	import { generateMappingCode } from '$lib/api/mappingEditor';
	import { generateMappingLine } from '$lib/utils/codeGenerator';
	import { onMount } from 'svelte';
	import type * as Monaco from 'monaco-editor';

	interface Props {
		datasetSpec?: string | null;
		selectedMappingId?: string | null;
		onMappingSelect?: (id: string) => void;
		onEditClick?: (mapping: Mapping) => void;
	}

	let {
		datasetSpec = null,
		selectedMappingId: propSelectedId = null,
		onMappingSelect,
		onEditClick
	}: Props = $props();

	// Track mappings from store
	let mappings = $state<Mapping[]>([]);

	// Search state for filtering mappings
	let searchQuery = $state('');

	// Currently selected mapping - use prop if provided, otherwise local state
	let localSelectedId = $state<string | null>(null);
	const selectedMappingId = $derived(propSelectedId ?? localSelectedId);

	// Filtered mappings based on search
	const filteredMappings = $derived.by(() => {
		if (!searchQuery.trim()) return mappings;
		const query = searchQuery.toLowerCase();
		return mappings.filter(m =>
			m.sourceName.toLowerCase().includes(query) ||
			m.targetName.toLowerCase().includes(query) ||
			m.sourcePath.toLowerCase().includes(query) ||
			m.targetPath.toLowerCase().includes(query)
		);
	});

	// Server-side code state
	let serverCode = $state<string>('');
	let isLoadingCode = $state(false);
	let codeError = $state<string | null>(null);

	// Monaco for syntax highlighting
	let monaco: typeof Monaco | null = null;
	let colorizedCodeHtml = $state<string>('');

	// Track modified paths from store
	let modifiedPathsSet = $state<Set<string>>(new Set());

	// Resizable panel state
	let listWidth = $state(220);
	let isResizing = $state(false);

	function handleResizeStart(e: MouseEvent) {
		e.preventDefault();
		isResizing = true;
		const startX = e.clientX;
		const startWidth = listWidth;

		function handleMouseMove(e: MouseEvent) {
			const delta = e.clientX - startX;
			listWidth = Math.max(150, Math.min(400, startWidth + delta));
		}

		function handleMouseUp() {
			isResizing = false;
			document.removeEventListener('mousemove', handleMouseMove);
			document.removeEventListener('mouseup', handleMouseUp);
		}

		document.addEventListener('mousemove', handleMouseMove);
		document.addEventListener('mouseup', handleMouseUp);
	}

	// Subscribe to stores and init Monaco
	onMount(() => {
		// Load Monaco for syntax highlighting
		import('monaco-editor').then((monacoModule) => {
			monaco = monacoModule;

			// Register Groovy if not already
			if (!monaco.languages.getLanguages().some(lang => lang.id === 'groovy')) {
				monaco.languages.register({ id: 'groovy' });
				monaco.languages.setMonarchTokensProvider('groovy', {
					defaultToken: '',
					tokenPostfix: '.groovy',
					keywords: [
						'def', 'if', 'else', 'for', 'while', 'return', 'true', 'false', 'null',
						'new', 'class', 'import', 'package', 'try', 'catch', 'finally', 'throw',
						'this', 'super', 'in', 'instanceof', 'as', 'assert'
					],
					operators: ['=', '>', '<', '!', '~', '?', ':', '==', '<=', '>=', '!=', '&&', '||', '++', '--', '+', '-', '*', '/', '&', '|', '^', '%', '<<', '>>', '>>>', '?.', '?:', '*.', '.&', '.@'],
					symbols: /[=><!~?:&|+\-*\/\^%]+/,
					escapes: /\\(?:[abfnrtv\\"']|x[0-9A-Fa-f]{1,4}|u[0-9A-Fa-f]{4})/,
					tokenizer: {
						root: [
							[/[a-zA-Z_$][\w$]*/, { cases: { '@keywords': 'keyword', '@default': 'identifier' } }],
							{ include: '@whitespace' },
							[/[{}()\[\]]/, '@brackets'],
							[/@symbols/, { cases: { '@operators': 'operator', '@default': '' } }],
							[/\d*\.\d+([eE][\-+]?\d+)?/, 'number.float'],
							[/\d+/, 'number'],
							[/[;,.]/, 'delimiter'],
							[/"([^"\\]|\\.)*$/, 'string.invalid'],
							[/"/, 'string', '@string_double'],
							[/'([^'\\]|\\.)*$/, 'string.invalid'],
							[/'/, 'string', '@string_single'],
						],
						whitespace: [
							[/[ \t\r\n]+/, ''],
							[/\/\*/, 'comment', '@comment'],
							[/\/\/.*$/, 'comment'],
						],
						comment: [
							[/[^\/*]+/, 'comment'],
							[/\*\//, 'comment', '@pop'],
							[/[\/*]/, 'comment']
						],
						string_double: [
							[/[^\\"]+/, 'string'],
							[/@escapes/, 'string.escape'],
							[/\\./, 'string.escape.invalid'],
							[/"/, 'string', '@pop']
						],
						string_single: [
							[/[^\\']+/, 'string'],
							[/@escapes/, 'string.escape'],
							[/\\./, 'string.escape.invalid'],
							[/'/, 'string', '@pop']
						]
					}
				});
			}
		});

		const unsubscribeMappings = mappingsStore.subscribe((m) => {
			mappings = m;
			// Auto-select first mapping if none selected
			if (m.length > 0 && !selectedMappingId) {
				selectMapping(m[0].id);
			}
		});

		const unsubscribeModified = modifiedPaths.subscribe((paths) => {
			modifiedPathsSet = paths;
		});

		return () => {
			unsubscribeMappings();
			unsubscribeModified();
		};
	});

	// Colorize code with Monaco
	async function colorizeGroovy(code: string): Promise<string> {
		if (!monaco || !code) {
			return escapeHtml(code || '');
		}
		try {
			return await monaco.editor.colorize(code, 'groovy', { tabSize: 2 });
		} catch (e) {
			return escapeHtml(code);
		}
	}

	function escapeHtml(text: string): string {
		return text
			.replace(/&/g, '&amp;')
			.replace(/</g, '&lt;')
			.replace(/>/g, '&gt;');
	}

	// Update selection
	function selectMapping(id: string) {
		if (onMappingSelect) {
			onMappingSelect(id);
		} else {
			localSelectedId = id;
		}
	}

	// Get the selected mapping
	const selectedMapping = $derived(
		mappings.find((m) => m.id === selectedMappingId) || null
	);

	// Get the current code for display (edited or server-generated)
	const displayCode = $derived.by(() => {
		if (!selectedMapping) return '';
		// Check if there's edited code in the store
		const editedCode = editedCodeStore.getCode(selectedMapping.targetPath);
		if (editedCode !== undefined) {
			return editedCode;
		}
		// Fall back to server code or generated
		return serverCode || generateMappingLine(selectedMapping);
	});

	// Check if current mapping has been edited
	const isEdited = $derived(
		selectedMapping ? modifiedPathsSet.has(selectedMapping.targetPath) : false
	);

	// Load server code when mapping selection changes
	$effect(() => {
		const mapping = selectedMapping;
		if (mapping && datasetSpec) {
			loadServerCode(mapping);
		}
	});

	// Update colorized HTML when code changes
	$effect(() => {
		const code = displayCode;
		if (code) {
			colorizeGroovy(code).then(html => {
				colorizedCodeHtml = html;
			});
		} else {
			colorizedCodeHtml = '';
		}
	});

	// Load code from server
	async function loadServerCode(mapping: Mapping) {
		if (!datasetSpec) return;

		isLoadingCode = true;
		codeError = null;

		try {
			const response = await generateMappingCode(datasetSpec, {
				inputPath: mapping.sourcePath,
				outputPath: mapping.targetPath
			});

			if (response.success) {
				serverCode = response.code;
			} else {
				codeError = 'Failed to generate code';
			}
		} catch (err) {
			codeError = err instanceof Error ? err.message : 'Failed to load code';
			// Fall back to client-side generation
			serverCode = generateMappingLine(mapping);
		} finally {
			isLoadingCode = false;
		}
	}

	// Check if code has been customized
	function isCustomized(mapping: Mapping): boolean {
		return modifiedPathsSet.has(mapping.targetPath);
	}
</script>

<div class="tweak-panel">
	{#if mappings.length === 0}
		<div class="empty-state">
			<p>No mappings defined yet.</p>
			<p class="hint">Drag fields from the source tree to the target tree to create mappings.</p>
		</div>
	{:else}
		<div class="tweak-layout" class:resizing={isResizing}>
			<!-- Mapping list sidebar -->
			<div class="mapping-list" style="width: {listWidth}px">
				<div class="list-header">
					<span class="header-title">Mappings ({mappings.length})</span>
				</div>
				<div class="search-box">
					<svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
						<circle cx="11" cy="11" r="8" />
						<path d="M21 21l-4.35-4.35" />
					</svg>
					<input
						type="text"
						class="search-input"
						placeholder="Filter mappings..."
						bind:value={searchQuery}
					/>
					{#if searchQuery}
						<button class="clear-btn" onclick={() => searchQuery = ''} aria-label="Clear">
							<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
								<path d="M18 6L6 18M6 6l12 12" />
							</svg>
						</button>
					{/if}
				</div>
				<div class="list-content">
					{#each filteredMappings as mapping (mapping.id)}
						<button
							class="mapping-item"
							class:selected={selectedMappingId === mapping.id}
							class:customized={isCustomized(mapping)}
							onclick={() => selectMapping(mapping.id)}
							ondblclick={() => onEditClick?.(mapping)}
						>
							<span class="source-name">{mapping.sourceName}</span>
							<span class="arrow">→</span>
							<span class="target-name">{mapping.targetName}</span>
							{#if isCustomized(mapping)}
								<span class="custom-badge">edited</span>
							{/if}
						</button>
					{/each}
					{#if searchQuery && filteredMappings.length === 0}
						<div class="no-results">No mappings match "{searchQuery}"</div>
					{/if}
				</div>
			</div>

			<!-- Resize handle -->
			<div
				class="resize-handle"
				onmousedown={handleResizeStart}
				role="separator"
				aria-orientation="vertical"
				title="Drag to resize"
			></div>

			<!-- Main tweak area -->
			<div class="tweak-main">
				{#if selectedMapping}
					<!-- Code display section - takes most of the space -->
					<div class="code-section">
						<div class="section-header">
							<div class="header-left">
								<span class="section-title">Groovy Code</span>
								{#if isEdited}
									<span class="edited-badge">edited</span>
								{/if}
							</div>
							<div class="section-actions">
								{#if isLoadingCode}
									<span class="loading-indicator">Loading...</span>
								{/if}
								{#if onEditClick}
									<button class="edit-btn" onclick={() => onEditClick(selectedMapping)} title="Open in full editor with preview">
										<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
											<path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
											<path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
										</svg>
										Edit
									</button>
								{/if}
							</div>
						</div>

						<!-- Mapping path info -->
						<div class="mapping-info">
							<span class="source">{selectedMapping.sourcePath}</span>
							<span class="arrow">→</span>
							<span class="target">{selectedMapping.targetPath}</span>
						</div>

						<!-- Code display (read-only with syntax highlighting) -->
						<div class="code-display">
							{#if codeError}
								<div class="code-error">{codeError}</div>
							{:else if isLoadingCode}
								<div class="code-loading">Loading code...</div>
							{:else}
								<pre class="code-content">{@html colorizedCodeHtml}</pre>
							{/if}
						</div>
					</div>

					<!-- Help hint pushed to bottom -->
					<div class="help-hint">
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
							<circle cx="12" cy="12" r="10" />
							<path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" />
							<line x1="12" y1="17" x2="12.01" y2="17" />
						</svg>
						<span>Double-click a mapping or click "Edit" to modify code and test with sample records</span>
					</div>
				{:else}
					<div class="empty-state">
						<p>Select a mapping to view its code</p>
					</div>
				{/if}
			</div>
		</div>
	{/if}
</div>

<style>
	.tweak-panel {
		height: 100%;
		display: flex;
		flex-direction: column;
		background: #111827;
	}

	.empty-state {
		flex: 1;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		color: #6b7280;
		text-align: center;
		padding: 24px;
	}

	.empty-state .hint {
		font-size: 12px;
		margin-top: 8px;
		color: #4b5563;
	}

	.tweak-layout {
		display: flex;
		height: 100%;
	}

	/* Mapping list sidebar */
	.mapping-list {
		min-width: 150px;
		max-width: 400px;
		display: flex;
		flex-direction: column;
		flex-shrink: 0;
		background: #111827;
	}

	/* Resize handle */
	.resize-handle {
		width: 4px;
		background: #374151;
		cursor: col-resize;
		flex-shrink: 0;
		transition: background 0.15s;
	}

	.resize-handle:hover,
	.tweak-layout.resizing .resize-handle {
		background: #3b82f6;
	}

	.tweak-layout.resizing {
		cursor: col-resize;
		user-select: none;
	}

	.list-header {
		padding: 8px 10px;
		font-size: 11px;
		font-weight: 500;
		text-transform: uppercase;
		color: #9ca3af;
		background: #1f2937;
		border-bottom: 1px solid #374151;
	}

	.header-title {
		display: block;
	}

	/* Search box */
	.search-box {
		position: relative;
		padding: 6px 8px;
		background: #1f2937;
		border-bottom: 1px solid #374151;
	}

	.search-box .search-icon {
		position: absolute;
		left: 14px;
		top: 50%;
		transform: translateY(-50%);
		width: 12px;
		height: 12px;
		color: #6b7280;
		pointer-events: none;
	}

	.search-box .search-input {
		width: 100%;
		padding: 5px 24px 5px 28px;
		font-size: 11px;
		border: 1px solid #374151;
		border-radius: 4px;
		background: #111827;
		color: #e5e7eb;
		outline: none;
	}

	.search-box .search-input:focus {
		border-color: #3b82f6;
	}

	.search-box .search-input::placeholder {
		color: #6b7280;
	}

	.search-box .clear-btn {
		position: absolute;
		right: 12px;
		top: 50%;
		transform: translateY(-50%);
		width: 16px;
		height: 16px;
		padding: 0;
		display: flex;
		align-items: center;
		justify-content: center;
		border: none;
		background: transparent;
		color: #6b7280;
		cursor: pointer;
		border-radius: 3px;
	}

	.search-box .clear-btn:hover {
		color: #e5e7eb;
		background: #374151;
	}

	.search-box .clear-btn svg {
		width: 12px;
		height: 12px;
	}

	.no-results {
		padding: 12px 8px;
		text-align: center;
		color: #6b7280;
		font-size: 11px;
	}

	.list-content {
		flex: 1;
		overflow-y: auto;
		padding: 4px;
	}

	.mapping-item {
		width: 100%;
		display: flex;
		align-items: center;
		gap: 4px;
		padding: 10px 8px;
		border: none;
		background: transparent;
		color: #d1d5db;
		font-size: 12px;
		text-align: left;
		cursor: pointer;
		border-radius: 4px;
		transition: background 0.1s;
	}

	.mapping-item:hover {
		background: #1f2937;
	}

	.mapping-item.selected {
		background: #1e3a5f;
	}

	.mapping-item .source-name {
		color: #60a5fa;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
		max-width: 70px;
	}

	.mapping-item .arrow {
		color: #4b5563;
		flex-shrink: 0;
	}

	.mapping-item .target-name {
		color: #4ade80;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
		flex: 1;
	}

	.mapping-item .custom-badge {
		margin-left: auto;
		font-size: 9px;
		padding: 1px 4px;
		border-radius: 3px;
		background: #854d0e;
		color: #fcd34d;
		flex-shrink: 0;
	}

	/* Main tweak area */
	.tweak-main {
		flex: 1;
		display: flex;
		flex-direction: column;
		padding: 12px;
		gap: 12px;
		overflow: hidden;
	}

	/* Code section - takes all available space */
	.code-section {
		flex: 1;
		display: flex;
		flex-direction: column;
		background: #1f2937;
		border-radius: 8px;
		overflow: hidden;
		min-height: 0;
	}

	.section-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 10px 14px;
		background: #374151;
		flex-shrink: 0;
	}

	.header-left {
		display: flex;
		align-items: center;
		gap: 10px;
	}

	.section-title {
		font-size: 11px;
		font-weight: 600;
		text-transform: uppercase;
		color: #9ca3af;
	}

	.edited-badge {
		font-size: 9px;
		padding: 2px 6px;
		border-radius: 4px;
		background: #854d0e;
		color: #fcd34d;
	}

	.section-actions {
		display: flex;
		align-items: center;
		gap: 8px;
	}

	.loading-indicator {
		font-size: 10px;
		color: #6b7280;
	}

	.edit-btn {
		display: flex;
		align-items: center;
		gap: 5px;
		padding: 6px 14px;
		font-size: 12px;
		background: #3b82f6;
		border: none;
		border-radius: 4px;
		color: white;
		cursor: pointer;
		transition: background 0.15s;
	}

	.edit-btn:hover {
		background: #2563eb;
	}

	.edit-btn svg {
		width: 14px;
		height: 14px;
	}

	/* Mapping path info */
	.mapping-info {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 8px 14px;
		background: #2d3748;
		border-bottom: 1px solid #374151;
		font-family: 'JetBrains Mono', monospace;
		font-size: 11px;
		flex-shrink: 0;
		overflow-x: auto;
	}

	.mapping-info .source {
		color: #60a5fa;
		white-space: nowrap;
	}

	.mapping-info .arrow {
		color: #6b7280;
		flex-shrink: 0;
	}

	.mapping-info .target {
		color: #4ade80;
		white-space: nowrap;
	}

	/* Code display */
	.code-display {
		flex: 1;
		overflow: auto;
		padding: 14px;
		background: #0d1117;
		min-height: 0;
	}

	.code-content {
		margin: 0;
		font-family: 'JetBrains Mono', 'Fira Code', monospace;
		font-size: 13px;
		line-height: 1.6;
		color: #e5e7eb;
		white-space: pre-wrap;
		word-break: break-word;
	}

	.code-error {
		color: #f87171;
		font-size: 12px;
	}

	.code-loading {
		color: #6b7280;
		font-size: 12px;
		font-style: italic;
	}

	/* Help hint at bottom */
	.help-hint {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 10px 12px;
		background: rgba(59, 130, 246, 0.08);
		border: 1px solid rgba(59, 130, 246, 0.15);
		border-radius: 6px;
		font-size: 11px;
		color: #93c5fd;
		flex-shrink: 0;
	}

	.help-hint svg {
		width: 14px;
		height: 14px;
		flex-shrink: 0;
		opacity: 0.6;
	}
</style>
