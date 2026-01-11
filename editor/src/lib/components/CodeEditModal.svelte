<script lang="ts">
	import type { Mapping } from '$lib/stores/mappingStore';
	import { sampleRecords, getValueFromPath } from '$lib/sampleData';
	import { sourcePathToGroovy, generateMappingLine } from '$lib/utils/codeGenerator';
	import { onMount, onDestroy } from 'svelte';
	import type * as Monaco from 'monaco-editor';
	import {
		getAllVariables,
		getAllFunctionCategories,
		type FunctionDoc,
		type VariableDoc,
		type CustomFunction,
		type CustomVariable
	} from '$lib/data/groovyReference';
	import { generateMappingCode, previewMappingCode, loadHistogram, type PreviewMappingCodeResponse, type HistogramData } from '$lib/api/mappingEditor';
	import { editedCodeStore } from '$lib/stores/editedCodeStore';

	interface Props {
		isOpen: boolean;
		mapping: Mapping | null;
		currentRecordIndex: number;
		customCode?: string;
		customFunctions?: CustomFunction[];
		customVariables?: CustomVariable[];
		datasetSpec?: string | null;  // For server-side operations
		totalRecords?: number;
		onClose: () => void;
		onSave: (mappingId: string, code: string) => void;
		onRecordChange: (index: number) => void;
	}

	let {
		isOpen,
		mapping,
		currentRecordIndex,
		customCode,
		customFunctions = [],
		customVariables = [],
		datasetSpec = null,
		totalRecords = 0,
		onClose,
		onSave,
		onRecordChange
	}: Props = $props();

	// Monaco editor state
	let editorContainer: HTMLDivElement;
	let editor: Monaco.editor.IStandaloneCodeEditor | null = null;
	let monaco: typeof Monaco | null = null;
	let monacoInitialized = $state(false);

	// Local code state for editing
	let editedCode = $state('');

	// Track if code has been modified in this session
	let hasChanges = $state(false);

	// Reference panel state
	let referencePanelOpen = $state(false);
	let referenceSearch = $state('');
	let expandedCategories = $state<Set<string>>(new Set(['Variables', 'Text Methods']));
	let selectedItem = $state<FunctionDoc | VariableDoc | null>(null);

	// Stats panel state
	let statsPanelOpen = $state(false);
	let statsLoading = $state(false);
	let statsError = $state<string | null>(null);
	let histogramData = $state<HistogramData | null>(null);

	// Server-side code and preview state
	let isLoadingCode = $state(false);
	let codeError = $state<string | null>(null);
	let serverCode = $state<string>('');
	let previewResult = $state<PreviewMappingCodeResponse | null>(null);
	let isLoadingPreview = $state(false);
	let previewError = $state<string | null>(null);

	// Debounce timer for preview
	let previewDebounceTimer: ReturnType<typeof setTimeout> | null = null;
	const PREVIEW_DEBOUNCE_MS = 500;

	// Toggle for showing input in preview
	let showInputPreview = $state(false);

	// Toggle for showing variables panel
	let showVariables = $state(true);

	// Colorized XML for preview
	let colorizedOutputHtml = $state<string>('');
	let colorizedInputHtml = $state<string>('');

	// Get variables and functions based on current mapping
	const variables = $derived(mapping ? getAllVariables(mapping.sourcePath, customVariables) : []);
	const functionCategories = $derived(getAllFunctionCategories(customFunctions));

	// Filter by search
	const filteredVariables = $derived(
		referenceSearch
			? variables.filter(
					(v) =>
						v.name.toLowerCase().includes(referenceSearch.toLowerCase()) ||
						v.description.toLowerCase().includes(referenceSearch.toLowerCase())
				)
			: variables
	);

	const filteredCategories = $derived(
		referenceSearch
			? functionCategories
					.map((cat) => ({
						...cat,
						functions: cat.functions.filter(
							(f) =>
								f.name.toLowerCase().includes(referenceSearch.toLowerCase()) ||
								f.description.toLowerCase().includes(referenceSearch.toLowerCase())
						)
					}))
					.filter((cat) => cat.functions.length > 0)
			: functionCategories
	);

	// Toggle category expansion
	function toggleCategory(categoryName: string) {
		const newSet = new Set(expandedCategories);
		if (newSet.has(categoryName)) {
			newSet.delete(categoryName);
		} else {
			newSet.add(categoryName);
		}
		expandedCategories = newSet;
	}

	// Insert text at cursor position in editor
	function insertAtCursor(text: string) {
		if (!editor) return;

		const selection = editor.getSelection();
		if (selection) {
			const op = {
				range: selection,
				text: text,
				forceMoveMarkers: true
			};
			editor.executeEdits('reference-insert', [op]);
			editor.focus();
		}
	}

	// Handle item click - insert and show docs
	function handleItemClick(item: FunctionDoc | VariableDoc) {
		selectedItem = item;
		insertAtCursor(item.insertText);
	}

	// Load code from server (when using real data)
	async function loadServerCode() {
		if (!datasetSpec || !mapping) return;

		isLoadingCode = true;
		codeError = null;

		try {
			// First check if there's locally edited code
			const editedCode = editedCodeStore.getCode(mapping.targetPath);
			if (editedCode !== undefined) {
				serverCode = editedCode;
				return;
			}

			// Otherwise fetch from server
			const response = await generateMappingCode(datasetSpec, {
				inputPath: mapping.sourcePath,
				outputPath: mapping.targetPath
			});

			if (response.success) {
				serverCode = response.code;
			} else {
				codeError = 'Failed to generate code';
				// Fall back to client-side generation
				serverCode = generateMappingLine(mapping);
			}
		} catch (err) {
			codeError = err instanceof Error ? err.message : 'Failed to load code';
			serverCode = generateMappingLine(mapping);
		} finally {
			isLoadingCode = false;
		}
	}

	// Execute preview on server
	async function executeServerPreview() {
		if (!datasetSpec || !mapping) return;

		isLoadingPreview = true;
		previewError = null;

		try {
			const response = await previewMappingCode(
				datasetSpec,
				{
					inputPath: mapping.sourcePath,
					outputPath: mapping.targetPath,
					groovyCode: editedCode
				},
				currentRecordIndex
			);
			previewResult = response;
		} catch (err) {
			previewError = err instanceof Error ? err.message : 'Preview failed';
			previewResult = null;
		} finally {
			isLoadingPreview = false;
		}
	}

	// Schedule a debounced preview
	function schedulePreview() {
		if (previewDebounceTimer) {
			clearTimeout(previewDebounceTimer);
		}
		previewDebounceTimer = setTimeout(() => {
			if (datasetSpec) {
				executeServerPreview();
			}
		}, PREVIEW_DEBOUNCE_MS);
	}

	// Load stats for the source field
	async function loadStats() {
		if (!datasetSpec || !mapping) return;

		statsLoading = true;
		statsError = null;

		try {
			histogramData = await loadHistogram(datasetSpec, mapping.sourcePath, 100);
		} catch (err) {
			statsError = err instanceof Error ? err.message : 'Failed to load stats';
			histogramData = null;
		} finally {
			statsLoading = false;
		}
	}

	// Load stats when panel opens
	$effect(() => {
		if (statsPanelOpen && datasetSpec && mapping && !histogramData && !statsLoading) {
			loadStats();
		}
	});

	// Initialize Monaco on mount
	onMount(async () => {
		const monacoModule = await import('monaco-editor');
		monaco = monacoModule;

		// Register Groovy language if not already registered
		if (!monaco.languages.getLanguages().some(lang => lang.id === 'groovy')) {
			monaco.languages.register({ id: 'groovy' });

			monaco.languages.setMonarchTokensProvider('groovy', {
				defaultToken: '',
				tokenPostfix: '.groovy',
				keywords: [
					'abstract', 'as', 'assert', 'boolean', 'break', 'byte', 'case', 'catch',
					'char', 'class', 'const', 'continue', 'def', 'default', 'do', 'double',
					'else', 'enum', 'extends', 'false', 'final', 'finally', 'float', 'for',
					'goto', 'if', 'implements', 'import', 'in', 'instanceof', 'int', 'interface',
					'long', 'native', 'new', 'null', 'package', 'private', 'protected', 'public',
					'return', 'short', 'static', 'strictfp', 'super', 'switch', 'synchronized',
					'this', 'throw', 'throws', 'trait', 'transient', 'true', 'try', 'void',
					'volatile', 'while', 'with'
				],
				operators: [
					'=', '>', '<', '!', '~', '?', ':', '==', '<=', '>=', '!=', '&&', '||',
					'++', '--', '+', '-', '*', '/', '&', '|', '^', '%', '<<', '>>', '>>>',
					'+=', '-=', '*=', '/=', '&=', '|=', '^=', '%=', '<<=', '>>=', '>>>='
				],
				symbols: /[=><!~?:&|+\-*\/\^%]+/,
				escapes: /\\(?:[abfnrtv\\"']|x[0-9A-Fa-f]{1,4}|u[0-9A-Fa-f]{4}|U[0-9A-Fa-f]{8})/,
				tokenizer: {
					root: [
						[/[a-zA-Z_$][\w$]*/, { cases: { '@keywords': 'keyword', '@default': 'identifier' } }],
						{ include: '@whitespace' },
						[/[{}()\[\]]/, '@brackets'],
						[/[<>](?!@symbols)/, '@brackets'],
						[/@symbols/, { cases: { '@operators': 'operator', '@default': '' } }],
						[/\d*\.\d+([eE][\-+]?\d+)?[fFdD]?/, 'number.float'],
						[/0[xX][0-9a-fA-F]+[lL]?/, 'number.hex'],
						[/\d+[lL]?/, 'number'],
						[/[;,.]/, 'delimiter'],
						[/"([^"\\]|\\.)*$/, 'string.invalid'],
						[/"/, 'string', '@string_double'],
						[/'([^'\\]|\\.)*$/, 'string.invalid'],
						[/'/, 'string', '@string_single'],
						[/\$\{/, 'string.interpolation', '@interpolation'],
						[/\$\w+/, 'string.interpolation'],
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
						[/[^\\"$]+/, 'string'],
						[/@escapes/, 'string.escape'],
						[/\\./, 'string.escape.invalid'],
						[/\$\{/, 'string.interpolation', '@interpolation'],
						[/\$\w+/, 'string.interpolation'],
						[/"/, 'string', '@pop']
					],
					string_single: [
						[/[^\\']+/, 'string'],
						[/@escapes/, 'string.escape'],
						[/\\./, 'string.escape.invalid'],
						[/'/, 'string', '@pop']
					],
					interpolation: [
						[/\}/, 'string.interpolation', '@pop'],
						{ include: 'root' }
					]
				}
			});

			monaco.languages.setLanguageConfiguration('groovy', {
				comments: { lineComment: '//', blockComment: ['/*', '*/'] },
				brackets: [['{', '}'], ['[', ']'], ['(', ')']],
				autoClosingPairs: [
					{ open: '{', close: '}' },
					{ open: '[', close: ']' },
					{ open: '(', close: ')' },
					{ open: '"', close: '"' },
					{ open: "'", close: "'" }
				],
				surroundingPairs: [
					{ open: '{', close: '}' },
					{ open: '[', close: ']' },
					{ open: '(', close: ')' },
					{ open: '"', close: '"' },
					{ open: "'", close: "'" }
				]
			});
		}

		// Define theme if not already defined
		try {
			monaco.editor.defineTheme('groovy-dark-modal', {
				base: 'vs-dark',
				inherit: true,
				rules: [
					{ token: 'keyword', foreground: 'c586c0' },
					{ token: 'string', foreground: 'ce9178' },
					{ token: 'string.interpolation', foreground: '4ec9b0' },
					{ token: 'number', foreground: 'b5cea8' },
					{ token: 'comment', foreground: '6a9955' },
					{ token: 'operator', foreground: 'd4d4d4' },
					{ token: 'identifier', foreground: '9cdcfe' }
				],
				colors: {
					'editor.background': '#0d1117',
					'editor.foreground': '#d4d4d4',
					'editor.lineHighlightBackground': '#161b22',
					'editorCursor.foreground': '#ffffff',
					'editor.selectionBackground': '#264f78',
					'editorLineNumber.foreground': '#5a5a5a',
					'editorLineNumber.activeForeground': '#c6c6c6'
				}
			});
		} catch (e) {
			// Theme already defined
		}

		monacoInitialized = true;
	});

	onDestroy(() => {
		editor?.dispose();
		editor = null;
	});

	// Create/update editor when modal opens
	$effect(() => {
		if (isOpen && mapping && monacoInitialized && editorContainer && monaco) {
			// Reset state
			hasChanges = false;
			previewResult = null;
			previewError = null;
			codeError = null;

			// Dispose old editor if exists
			if (editor) {
				editor.dispose();
				editor = null;
			}

			// Load code: server code for real data, or client-side generation for sample
			if (datasetSpec) {
				// Load from server
				loadServerCode().then(() => {
					createEditor(serverCode || generateMappingLine(mapping));
					// Trigger initial preview
					executeServerPreview();
				});
			} else {
				// Use client-side generation
				const initialCode = customCode ?? generateMappingLine(mapping);
				editedCode = initialCode;
				createEditor(initialCode);
			}
		}
	});

	// Helper to create the Monaco editor
	function createEditor(initialCode: string) {
		if (!monaco || !editorContainer) return;

		editedCode = initialCode;

		// Create new editor after a tick to ensure container is rendered
		setTimeout(() => {
			if (!monaco || !editorContainer) return;

			editor = monaco.editor.create(editorContainer, {
				value: initialCode,
				language: 'groovy',
				theme: 'groovy-dark-modal',
				minimap: { enabled: false },
				fontSize: 14,
				fontFamily: "'JetBrains Mono', 'Fira Code', 'SF Mono', Consolas, monospace",
				lineNumbers: 'on',
				scrollBeyondLastLine: false,
				automaticLayout: true,
				tabSize: 4,
				insertSpaces: false,
				wordWrap: 'on',
				folding: true,
				renderLineHighlight: 'line',
				scrollbar: {
					vertical: 'auto',
					horizontal: 'auto',
					verticalScrollbarSize: 10,
					horizontalScrollbarSize: 10
				},
				padding: { top: 12, bottom: 12 }
			});

			editor.onDidChangeModelContent(() => {
				editedCode = editor?.getValue() ?? '';
				hasChanges = true;
				// Schedule preview when code changes (for real data)
				if (datasetSpec) {
					schedulePreview();
				}
			});
		}, 0);
	}

	// Cleanup editor when modal closes
	$effect(() => {
		if (!isOpen && editor) {
			editor.dispose();
			editor = null;
		}
	});

	// Get the current record (for sample data)
	const currentRecord = $derived(sampleRecords[currentRecordIndex]);
	// Use prop totalRecords if provided (real data), otherwise use sample length
	const effectiveTotalRecords = $derived(totalRecords > 0 ? totalRecords : sampleRecords.length);

	// Extract source value from the current record (for sample data)
	function getSampleSourceValue(): string {
		if (!currentRecord || !mapping) return '';
		const value = getValueFromPath(currentRecord, mapping.sourcePath);
		if (value === undefined) return '';
		if (Array.isArray(value)) {
			return value.join('\n');
		}
		return value;
	}

	// Get source value - server data or sample data
	function getSourceValue(): string {
		if (datasetSpec && previewResult?.inputXml) {
			return previewResult.inputXml;
		}
		return getSampleSourceValue();
	}

	// Check if input is empty for the current record
	const isInputEmpty = $derived(
		datasetSpec && previewResult
			? (!previewResult.inputXml || previewResult.inputXml.trim() === '')
			: !getSampleSourceValue()
	);

	// Check if all variables are empty (no data for this record)
	const allVariablesEmpty = $derived.by(() => {
		if (!previewResult?.variableBindings) return false;
		const bindings = previewResult.variableBindings;
		const keys = Object.keys(bindings);
		if (keys.length === 0) return false;
		// Check if ALL values are empty/not found/error
		return keys.every(key => {
			const val = bindings[key];
			return val === '(empty)' || val === '(not found)' || val === '(error)';
		});
	});

	// Colorize XML using Monaco
	async function colorizeXml(xml: string): Promise<string> {
		if (!monaco || !xml || xml.startsWith('(') || xml.startsWith('Error:')) {
			// Return escaped HTML for placeholder/error text or if Monaco not ready
			return escapeHtml(xml);
		}

		try {
			// Ensure XML language is registered
			if (!monaco.languages.getLanguages().some(lang => lang.id === 'xml')) {
				monaco.languages.register({ id: 'xml' });
			}

			// Use Monaco's colorize function
			const colorized = await monaco.editor.colorize(xml, 'xml', { tabSize: 2 });
			return colorized;
		} catch (e) {
			// Fallback to escaped HTML on error
			console.warn('Monaco colorize failed:', e);
			return escapeHtml(xml);
		}
	}

	// Escape HTML for safe display
	function escapeHtml(text: string): string {
		return text
			.replace(/&/g, '&amp;')
			.replace(/</g, '&lt;')
			.replace(/>/g, '&gt;')
			.replace(/"/g, '&quot;')
			.replace(/'/g, '&#039;');
	}

	// Update colorized HTML when preview results change
	$effect(() => {
		if (!isOpen || !monacoInitialized) return;

		const outputVal = getOutputValue();
		const inputVal = getSourceValue();

		// Colorize output
		colorizeXml(outputVal).then(html => {
			colorizedOutputHtml = html;
		});

		// Colorize input
		colorizeXml(inputVal).then(html => {
			colorizedInputHtml = html;
		});
	});

	// Get output value - server data or sample simulation
	function getOutputValue(): string {
		if (datasetSpec) {
			if (previewResult?.success === false) {
				return `Error: ${previewResult.error || 'Unknown error'}`;
			}
			if (previewResult?.outputXml) {
				return previewResult.outputXml;
			}
			return isLoadingPreview ? '(loading...)' : '(no output)';
		}
		// For sample data, just return the source value as simulation
		return getSampleSourceValue();
	}

	// Reset to generated code
	function handleReset() {
		if (mapping && editor) {
			const defaultCode = generateMappingLine(mapping);
			editor.setValue(defaultCode);
			editedCode = defaultCode;
			hasChanges = true;
		}
	}

	// Save and close
	function handleSave() {
		if (mapping) {
			// Save to the edited code store for local tracking
			editedCodeStore.setCode(mapping.targetPath, editedCode);
			// Also call the callback
			onSave(mapping.id, editedCode);
			onClose();
		}
	}

	// Navigation
	function prevRecord() {
		if (currentRecordIndex > 0) {
			onRecordChange(currentRecordIndex - 1);
			// Refresh preview for new record
			if (datasetSpec) {
				setTimeout(executeServerPreview, 100);
			}
		}
	}

	function nextRecord() {
		if (currentRecordIndex < effectiveTotalRecords - 1) {
			onRecordChange(currentRecordIndex + 1);
			// Refresh preview for new record
			if (datasetSpec) {
				setTimeout(executeServerPreview, 100);
			}
		}
	}

	// Handle escape key
	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			onClose();
		}
	}

	// Handle backdrop click
	function handleBackdropClick(e: MouseEvent) {
		if (e.target === e.currentTarget) {
			onClose();
		}
	}
</script>

<svelte:window onkeydown={handleKeydown} />

{#if isOpen && mapping}
	<div
		class="modal-backdrop"
		onclick={handleBackdropClick}
		onkeydown={(e) => { if (e.key === 'Escape') onClose(); }}
		role="dialog"
		aria-modal="true"
		tabindex="-1"
	>
		<div class="modal" class:panel-open={referencePanelOpen}>
			<!-- Header -->
			<div class="modal-header">
				<div class="header-left">
					<h2 class="modal-title">Edit Mapping Code</h2>
				</div>
				<div class="header-right">
					<button
						class="reference-toggle"
						class:active={statsPanelOpen}
						onclick={() => (statsPanelOpen = !statsPanelOpen)}
						title="Toggle field statistics"
					>
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
							<path d="M18 20V10M12 20V4M6 20v-6" />
						</svg>
						Stats
					</button>
					<button
						class="reference-toggle"
						class:active={referencePanelOpen}
						onclick={() => (referencePanelOpen = !referencePanelOpen)}
						title="Toggle function reference"
					>
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
							<path d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" />
						</svg>
						Reference
					</button>
					<button class="close-btn" onclick={onClose} aria-label="Close">
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
							<path d="M18 6L6 18M6 6l12 12" />
						</svg>
					</button>
				</div>
			</div>

			<!-- Full path info bar -->
			<div class="path-info-bar">
				<span class="path-label">Source:</span>
				<span class="source-path">{mapping.sourcePath}</span>
				<span class="path-arrow">→</span>
				<span class="path-label">Target:</span>
				<span class="target-path-full">{mapping.targetPath}</span>
			</div>

			<!-- Content wrapper for main + side panels -->
			<div class="modal-body" class:panel-open={referencePanelOpen || statsPanelOpen}>
				<!-- Main Content -->
				<div class="modal-content">
					<!-- Code editor section -->
					<div class="code-section">
						<div class="section-header">
							<span class="section-title">Groovy Code</span>
							<div class="section-actions">
								{#if isLoadingCode}
									<span class="loading-badge">Loading...</span>
								{/if}
								{#if codeError}
									<span class="error-badge">{codeError}</span>
								{/if}
								{#if hasChanges}
									<span class="modified-badge">Modified</span>
								{/if}
								<button class="action-btn" onclick={handleReset}>Reset to Default</button>
							</div>
						</div>
						<div class="code-editor" bind:this={editorContainer}></div>
					</div>

					<!-- Preview section -->
					<div class="preview-section" class:input-expanded={showInputPreview}>
						<!-- Header with record navigation and input toggle -->
						<div class="preview-header">
							<div class="preview-title">
								<span class="title-text">Output Preview</span>
								<span class="target-path">{mapping.targetName}</span>
								{#if isLoadingPreview}
									<div class="preview-spinner-small"></div>
								{/if}
								{#if isInputEmpty && !isLoadingPreview}
									<span class="empty-input-badge" title="This record has no value at the source path - this is expected for some records">
										<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
											<circle cx="12" cy="12" r="10" />
											<line x1="12" y1="8" x2="12" y2="12" />
											<line x1="12" y1="16" x2="12.01" y2="16" />
										</svg>
										No input
									</span>
								{/if}
								{#if previewResult?.success === false && !isInputEmpty}
									<span class="output-error-badge">Error</span>
								{/if}
							</div>
							<div class="preview-controls">
								<button
									class="input-toggle"
									class:active={showVariables}
									onclick={() => showVariables = !showVariables}
									title={showVariables ? 'Hide variables' : 'Show variables'}
								>
									<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
										<path d="M4 7h16M4 12h16M4 17h10" />
									</svg>
									Vars
									{#if previewResult?.variableBindings && Object.keys(previewResult.variableBindings).length > 0}
										<span class="var-count">{Object.keys(previewResult.variableBindings).length}</span>
									{/if}
								</button>
								<button
									class="input-toggle"
									class:active={showInputPreview}
									onclick={() => showInputPreview = !showInputPreview}
									title={showInputPreview ? 'Hide input' : 'Show input'}
								>
									<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
										<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
										<polyline points="14 2 14 8 20 8" />
										<line x1="16" y1="13" x2="8" y2="13" />
										<line x1="16" y1="17" x2="8" y2="17" />
									</svg>
									Input
								</button>
								<div class="record-nav-compact">
									<button
										class="nav-btn-small"
										onclick={prevRecord}
										disabled={currentRecordIndex === 0}
										aria-label="Previous record"
									>
										<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
											<path d="M15 19l-7-7 7-7" />
										</svg>
									</button>
									<span class="record-info-compact">
										{currentRecordIndex + 1}/{effectiveTotalRecords}
										{#if previewResult?.recordId}
											<span class="record-id">({previewResult.recordId})</span>
										{/if}
									</span>
									<button
										class="nav-btn-small"
										onclick={nextRecord}
										disabled={currentRecordIndex >= effectiveTotalRecords - 1}
										aria-label="Next record"
									>
										<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
											<path d="M9 5l7 7-7 7" />
										</svg>
									</button>
								</div>
							</div>
						</div>

						<!-- Main preview area -->
						<div class="preview-panels">
							<!-- Variables panel (shows actual values from current record) -->
							{#if showVariables && previewResult?.variableBindings && Object.keys(previewResult.variableBindings).length > 0}
								<div class="variables-panel" class:all-empty={allVariablesEmpty}>
									<div class="panel-label">
										<span>Variables</span>
										{#if allVariablesEmpty}
											<span class="all-empty-warning" title="All variables are empty for this record - try a different record">
												<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
													<path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
													<line x1="12" y1="9" x2="12" y2="13"/>
													<line x1="12" y1="17" x2="12.01" y2="17"/>
												</svg>
												All empty
											</span>
										{:else}
											<span class="var-hint">Values from current record</span>
										{/if}
									</div>
									<div class="variables-list">
										{#each Object.entries(previewResult.variableBindings) as [varName, value]}
											<div class="variable-row" class:empty={value === '(empty)' || value === '(not found)'}>
												<span class="var-name">{varName}</span>
												<span class="var-value" title={value}>
													{#if value === '(empty)'}
														<span class="var-empty">(empty)</span>
													{:else if value === '(not found)'}
														<span class="var-not-found">(not found)</span>
													{:else if value === '(error)'}
														<span class="var-error">(error)</span>
													{:else}
														{value.length > 80 ? value.substring(0, 80) + '...' : value}
													{/if}
												</span>
											</div>
										{/each}
									</div>
								</div>
							{:else if showVariables && !isLoadingPreview && datasetSpec}
								<div class="variables-panel empty-state">
									<div class="panel-label">
										<span>Variables</span>
									</div>
									<div class="variables-empty">
										No variables detected in the current code
									</div>
								</div>
							{/if}

							<!-- Collapsible Input panel -->
							{#if showInputPreview}
								<div class="input-panel">
									<div class="panel-label">
										<span>Input</span>
										<span class="input-path">{sourcePathToGroovy(mapping.sourcePath)}</span>
									</div>
									<div class="panel-content xml-content" class:loading={isLoadingPreview}>
										<pre>{@html colorizedInputHtml || escapeHtml('(empty)')}</pre>
									</div>
								</div>
							{/if}

							<!-- Output panel (always visible, takes full height when input hidden) -->
							<div class="output-panel">
								<div class="panel-content xml-content" class:loading={isLoadingPreview} class:error={previewResult?.success === false}>
									<pre>{@html colorizedOutputHtml || escapeHtml('(empty)')}</pre>
								</div>
							</div>
						</div>

						{#if previewError}
							<div class="preview-error">{previewError}</div>
						{/if}
					</div>
				</div>

				<!-- Reference Panel (slide-out) -->
				{#if referencePanelOpen}
					<div class="reference-panel">
						<div class="reference-header">
							<span class="reference-title">Reference</span>
							<button
								class="reference-close"
								onclick={() => (referencePanelOpen = false)}
								aria-label="Close reference panel"
							>
								<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
									<path d="M18 6L6 18M6 6l12 12" />
								</svg>
							</button>
						</div>

						<!-- Search -->
						<div class="reference-search">
							<input
								type="text"
								placeholder="Search functions..."
								bind:value={referenceSearch}
								class="search-input"
							/>
						</div>

						<!-- Reference list -->
						<div class="reference-list">
							<!-- Variables section -->
							{#if filteredVariables.length > 0}
								<div class="reference-category">
									<button
										class="category-header"
										onclick={() => toggleCategory('Variables')}
									>
										<svg
											class="expand-icon"
											class:expanded={expandedCategories.has('Variables')}
											viewBox="0 0 24 24"
											fill="none"
											stroke="currentColor"
											stroke-width="2"
										>
											<path d="M9 5l7 7-7 7" />
										</svg>
										<span class="category-name">Variables</span>
										<span class="category-count">{filteredVariables.length}</span>
									</button>
									{#if expandedCategories.has('Variables')}
										<div class="category-items">
											{#each filteredVariables as variable}
												<button
													class="reference-item"
													class:selected={selectedItem === variable}
													onclick={() => handleItemClick(variable)}
													title={variable.description}
												>
													<span class="item-name">{variable.name}</span>
													<span class="item-type">{variable.type}</span>
												</button>
											{/each}
										</div>
									{/if}
								</div>
							{/if}

							<!-- Function categories -->
							{#each filteredCategories as category}
								<div class="reference-category">
									<button
										class="category-header"
										onclick={() => toggleCategory(category.name)}
									>
										<svg
											class="expand-icon"
											class:expanded={expandedCategories.has(category.name)}
											viewBox="0 0 24 24"
											fill="none"
											stroke="currentColor"
											stroke-width="2"
										>
											<path d="M9 5l7 7-7 7" />
										</svg>
										<span class="category-name">{category.name}</span>
										<span class="category-count">{category.functions.length}</span>
									</button>
									{#if expandedCategories.has(category.name)}
										<div class="category-items">
											{#each category.functions as fn}
												<button
													class="reference-item"
													class:selected={selectedItem === fn}
													onclick={() => handleItemClick(fn)}
													title={fn.description}
												>
													<span class="item-name">{fn.name}</span>
												</button>
											{/each}
										</div>
									{/if}
								</div>
							{/each}
						</div>

						<!-- Selected item documentation -->
						{#if selectedItem}
							<div class="reference-docs">
								<div class="docs-header">
									<span class="docs-name">
										{'signature' in selectedItem ? selectedItem.signature : selectedItem.name}
									</span>
								</div>
								<div class="docs-content">
									<p class="docs-description">{selectedItem.description}</p>
									{#if 'example' in selectedItem && selectedItem.example}
										<div class="docs-example">
											<span class="example-label">Example:</span>
											<code>{selectedItem.example}</code>
										</div>
									{/if}
								</div>
							</div>
						{/if}
					</div>
				{/if}

				<!-- Stats Panel (slide-out) -->
				{#if statsPanelOpen}
					<div class="stats-panel">
						<div class="reference-header">
							<span class="reference-title">Field Stats</span>
							<button
								class="reference-close"
								onclick={() => (statsPanelOpen = false)}
								aria-label="Close stats panel"
							>
								<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
									<path d="M18 6L6 18M6 6l12 12" />
								</svg>
							</button>
						</div>

						{#if statsLoading}
							<div class="stats-loading">
								<div class="stats-spinner"></div>
								<span>Loading statistics...</span>
							</div>
						{:else if statsError}
							<div class="stats-error">{statsError}</div>
						{:else}
							<!-- Field path -->
							<div class="stats-field-path">
								<span class="stats-label">Source field</span>
								<code>{mapping.sourcePath}</code>
							</div>

							<!-- Summary metrics from histogram -->
							{#if histogramData}
								<div class="stats-section">
									<div class="stats-section-title">Summary</div>
									<div class="stats-metric">
										<span class="metric-label">Total values</span>
										<span class="metric-value">{histogramData.entries.toLocaleString()}</span>
									</div>
									<div class="stats-metric">
										<span class="metric-label">Unique values</span>
										<span class="metric-value">{histogramData.uniqueCount.toLocaleString()}</span>
									</div>
									{#if !histogramData.complete}
										<div class="stats-note">
											Showing top {histogramData.currentSize} values
										</div>
									{/if}
								</div>
							{/if}

							<!-- Histogram -->
							{#if histogramData && histogramData.values.length > 0}
								<div class="stats-section">
									<div class="stats-section-title">
										Value Distribution
									</div>
									<div class="histogram-list">
										{#each histogramData.values.slice(0, 10) as item}
											{@const maxCount = histogramData.values[0]?.count || 1}
											{@const barWidth = (item.count / maxCount) * 100}
											<div class="histogram-row">
												<div class="histogram-bar-bg">
													<div class="histogram-bar" style="width: {barWidth}%"></div>
												</div>
												<span class="histogram-count">{item.count}</span>
												<span class="histogram-value" title={item.value}>
													{item.value.length > 30 ? item.value.substring(0, 30) + '...' : item.value}
												</span>
											</div>
										{/each}
										{#if histogramData.values.length > 10}
											<div class="histogram-more">
												+{histogramData.values.length - 10} more values
											</div>
										{/if}
									</div>
								</div>
							{:else if histogramData}
								<div class="stats-empty">No values found</div>
							{/if}
						{/if}
					</div>
				{/if}
			</div>

			<!-- Footer -->
			<div class="modal-footer">
				<button class="btn btn-secondary" onclick={onClose}>Cancel</button>
				<button class="btn btn-primary" onclick={handleSave}>
					Save Changes
				</button>
			</div>
		</div>
	</div>
{/if}

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
		max-width: 1000px;
		height: 85vh;
		display: flex;
		flex-direction: column;
		box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5);
		border: 1px solid #374151;
		transition: max-width 0.2s ease;
	}

	.modal.panel-open {
		max-width: 1280px;
	}

	.modal-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 16px 20px;
		border-bottom: 1px solid #374151;
	}

	.header-left {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}

	.modal-title {
		font-size: 16px;
		font-weight: 600;
		color: #f3f4f6;
		margin: 0;
	}

	/* Full path info bar */
	.path-info-bar {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 10px 20px;
		background: #111827;
		border-bottom: 1px solid #374151;
		font-family: 'JetBrains Mono', ui-monospace, monospace;
		font-size: 11px;
		overflow-x: auto;
		flex-shrink: 0;
	}

	.path-label {
		color: #6b7280;
		font-weight: 500;
		text-transform: uppercase;
		font-size: 10px;
		flex-shrink: 0;
	}

	.source-path {
		color: #60a5fa;
		white-space: nowrap;
	}

	.path-arrow {
		color: #4b5563;
		flex-shrink: 0;
	}

	.target-path-full {
		color: #4ade80;
		white-space: nowrap;
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

	.modal-content {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-height: 0;
		overflow: hidden;
	}

	/* Code section */
	.code-section {
		display: flex;
		flex-direction: column;
		border-bottom: 1px solid #374151;
		flex: 1.5;
		min-height: 300px;
	}

	.section-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 10px 16px;
		background: #111827;
		border-bottom: 1px solid #374151;
		flex-shrink: 0;
	}

	.section-title {
		font-size: 11px;
		font-weight: 500;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: #9ca3af;
	}

	.section-actions {
		display: flex;
		align-items: center;
		gap: 10px;
	}

	.modified-badge {
		font-size: 10px;
		padding: 2px 8px;
		border-radius: 10px;
		background: rgba(251, 191, 36, 0.2);
		color: #fbbf24;
	}

	.loading-badge {
		font-size: 10px;
		padding: 2px 8px;
		border-radius: 10px;
		background: rgba(96, 165, 250, 0.2);
		color: #60a5fa;
	}

	.error-badge {
		font-size: 10px;
		padding: 2px 8px;
		border-radius: 10px;
		background: rgba(239, 68, 68, 0.2);
		color: #ef4444;
	}

	.output-error-badge {
		font-size: 9px;
		padding: 1px 4px;
		border-radius: 3px;
		background: #7f1d1d;
		color: #fca5a5;
	}

	.empty-input-badge {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		font-size: 10px;
		padding: 2px 6px;
		border-radius: 4px;
		background: rgba(251, 191, 36, 0.15);
		color: #fbbf24;
		cursor: help;
	}

	.empty-input-badge svg {
		width: 12px;
		height: 12px;
	}

	.record-id {
		font-size: 10px;
		color: #6b7280;
		margin-left: 4px;
	}


	.preview-error {
		padding: 8px 12px;
		background: #7f1d1d;
		border-radius: 4px;
		font-size: 12px;
		color: #fca5a5;
		margin-top: 8px;
	}

	.action-btn {
		padding: 4px 10px;
		font-size: 11px;
		background: #374151;
		border: none;
		border-radius: 4px;
		color: #d1d5db;
		cursor: pointer;
		transition: background 0.15s;
	}

	.action-btn:hover {
		background: #4b5563;
	}

	.code-editor {
		flex: 1;
		min-height: 0;
		border-radius: 0;
		overflow: hidden;
	}

	/* Preview section */
	.preview-section {
		flex: 1 1 auto;
		display: flex;
		flex-direction: column;
		min-height: 160px;
		max-height: 300px;
		border-top: 1px solid #374151;
		transition: max-height 0.2s ease;
	}

	.preview-section.input-expanded {
		max-height: 380px;
	}

	.preview-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 8px 16px;
		background: #111827;
		border-bottom: 1px solid #374151;
	}

	.preview-title {
		display: flex;
		align-items: center;
		gap: 8px;
	}

	.title-text {
		font-size: 11px;
		font-weight: 500;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: #9ca3af;
	}

	.target-path {
		font-family: ui-monospace, monospace;
		font-size: 12px;
		color: #4ade80;
	}

	.preview-spinner-small {
		width: 12px;
		height: 12px;
		border: 2px solid #374151;
		border-top-color: #3b82f6;
		border-radius: 50%;
		animation: spin 0.8s linear infinite;
	}

	.preview-controls {
		display: flex;
		align-items: center;
		gap: 12px;
	}

	.input-toggle {
		display: flex;
		align-items: center;
		gap: 4px;
		padding: 4px 8px;
		font-size: 11px;
		background: #374151;
		border: none;
		border-radius: 4px;
		color: #9ca3af;
		cursor: pointer;
		transition: all 0.15s;
	}

	.input-toggle:hover {
		background: #4b5563;
		color: #e5e7eb;
	}

	.input-toggle.active {
		background: #1e3a5f;
		color: #60a5fa;
	}

	.input-toggle svg {
		width: 12px;
		height: 12px;
	}

	.record-nav-compact {
		display: flex;
		align-items: center;
		gap: 6px;
	}

	.nav-btn-small {
		width: 24px;
		height: 24px;
		display: flex;
		align-items: center;
		justify-content: center;
		background: #374151;
		border: none;
		border-radius: 4px;
		color: #d1d5db;
		cursor: pointer;
		transition: background 0.15s;
	}

	.nav-btn-small:hover:not(:disabled) {
		background: #4b5563;
	}

	.nav-btn-small:disabled {
		opacity: 0.4;
		cursor: not-allowed;
	}

	.nav-btn-small svg {
		width: 14px;
		height: 14px;
	}

	.record-info-compact {
		font-size: 11px;
		color: #9ca3af;
		font-family: ui-monospace, monospace;
	}

	.preview-panels {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-height: 0;
		overflow: hidden;
	}

	.input-panel {
		flex: 0 0 auto;
		max-height: 100px;
		display: flex;
		flex-direction: column;
		background: #0d1117;
		border-bottom: 1px solid #374151;
	}

	/* Variables panel styles */
	.variables-panel {
		flex: 0 0 auto;
		max-height: 120px;
		display: flex;
		flex-direction: column;
		background: #0d1117;
		border-bottom: 1px solid #374151;
	}

	.variables-panel.empty-state {
		max-height: 60px;
	}

	.variables-panel.all-empty {
		background: #1a1a0d;
		border-bottom-color: #4d4d00;
	}

	.variables-panel.all-empty .panel-label {
		background: #2a2a0d;
	}

	.var-hint {
		font-size: 9px;
		color: #6b7280;
		margin-left: auto;
	}

	.all-empty-warning {
		display: inline-flex;
		align-items: center;
		gap: 4px;
		font-size: 10px;
		padding: 2px 6px;
		border-radius: 4px;
		background: rgba(251, 191, 36, 0.15);
		color: #fbbf24;
		margin-left: auto;
	}

	.all-empty-warning svg {
		width: 12px;
		height: 12px;
	}

	.variables-list {
		flex: 1;
		overflow-y: auto;
		padding: 6px 12px;
	}

	.variable-row {
		display: flex;
		align-items: flex-start;
		gap: 8px;
		padding: 4px 0;
		border-bottom: 1px solid #1f2937;
	}

	.variable-row:last-child {
		border-bottom: none;
	}

	.variable-row.empty {
		opacity: 0.6;
	}

	.var-name {
		font-family: 'JetBrains Mono', ui-monospace, monospace;
		font-size: 12px;
		color: #60a5fa;
		font-weight: 500;
		flex-shrink: 0;
		min-width: 100px;
	}

	.var-value {
		font-family: 'JetBrains Mono', ui-monospace, monospace;
		font-size: 11px;
		color: #e5e7eb;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
		flex: 1;
	}

	.var-empty,
	.var-not-found {
		color: #6b7280;
		font-style: italic;
	}

	.var-error {
		color: #ef4444;
		font-style: italic;
	}

	.variables-empty {
		padding: 10px 12px;
		color: #6b7280;
		font-size: 11px;
		font-style: italic;
	}

	.var-count {
		font-size: 9px;
		padding: 1px 4px;
		border-radius: 8px;
		background: rgba(96, 165, 250, 0.3);
		color: #60a5fa;
		margin-left: 4px;
	}

	.panel-label {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 6px 12px;
		background: #1f2937;
		font-size: 10px;
		font-weight: 500;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: #6b7280;
	}

	.input-path {
		font-family: ui-monospace, monospace;
		font-size: 10px;
		color: #60a5fa;
		text-transform: none;
		letter-spacing: normal;
	}

	.output-panel {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-height: 0;
		background: #0d1117;
	}

	.panel-content {
		flex: 1;
		padding: 10px 14px;
		overflow-y: auto;
		overflow-x: auto;
		min-height: 60px;
		max-height: 100%;
	}

	.panel-content pre {
		margin: 0;
		font-family: 'JetBrains Mono', ui-monospace, monospace;
		font-size: 13px;
		color: #e5e7eb;
		white-space: pre-wrap;
		word-break: break-word;
	}

	.panel-content.loading {
		opacity: 0.5;
	}

	.panel-content.error {
		background: #1f1315;
	}

	.panel-content.error pre {
		color: #fca5a5;
	}

	/* Monaco colorized XML content */
	.xml-content pre {
		line-height: 1.5;
	}

	.xml-content pre :global(span) {
		font-family: inherit;
	}

	/* Footer */
	.modal-footer {
		display: flex;
		justify-content: flex-end;
		gap: 12px;
		padding: 16px 20px;
		border-top: 1px solid #374151;
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

	.btn-secondary {
		background: #374151;
		color: #e5e7eb;
	}

	.btn-secondary:hover {
		background: #4b5563;
	}

	.btn-primary {
		background: #3b82f6;
		color: white;
	}

	.btn-primary:hover {
		background: #2563eb;
	}

	/* Header right section */
	.header-right {
		display: flex;
		align-items: center;
		gap: 8px;
	}

	.reference-toggle {
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

	.reference-toggle:hover {
		background: #4b5563;
	}

	.reference-toggle.active {
		background: #3b82f6;
		color: white;
	}

	.reference-toggle svg {
		width: 16px;
		height: 16px;
	}

	/* Modal body - wraps main content and reference panel */
	.modal-body {
		flex: 1;
		display: flex;
		min-height: 0;
		overflow: hidden;
		transition: all 0.2s ease;
	}

	.modal-body.panel-open .modal-content {
		flex: 1;
		min-width: 0;
	}

	/* Reference Panel */
	.reference-panel {
		width: 280px;
		flex-shrink: 0;
		display: flex;
		flex-direction: column;
		background: #111827;
		border-left: 1px solid #374151;
		animation: slideIn 0.2s ease;
	}

	/* Stats Panel */
	.stats-panel {
		width: 280px;
		flex-shrink: 0;
		display: flex;
		flex-direction: column;
		background: #111827;
		border-left: 1px solid #374151;
		animation: slideIn 0.2s ease;
		overflow-y: auto;
	}

	.stats-loading {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		padding: 40px 20px;
		gap: 12px;
		color: #9ca3af;
		font-size: 12px;
	}

	.stats-spinner {
		width: 24px;
		height: 24px;
		border: 2px solid #374151;
		border-top-color: #3b82f6;
		border-radius: 50%;
		animation: spin 0.8s linear infinite;
	}

	@keyframes spin {
		to { transform: rotate(360deg); }
	}

	.stats-error {
		padding: 20px;
		color: #ef4444;
		font-size: 12px;
		text-align: center;
	}

	.stats-field-path {
		padding: 12px;
		border-bottom: 1px solid #374151;
	}

	.stats-label {
		display: block;
		font-size: 10px;
		text-transform: uppercase;
		color: #6b7280;
		margin-bottom: 4px;
	}

	.stats-field-path code {
		font-family: 'JetBrains Mono', ui-monospace, monospace;
		font-size: 11px;
		color: #60a5fa;
		word-break: break-all;
	}

	.stats-section {
		padding: 12px;
		border-bottom: 1px solid #374151;
	}

	.stats-section-title {
		font-size: 11px;
		font-weight: 500;
		text-transform: uppercase;
		color: #9ca3af;
		margin-bottom: 10px;
		display: flex;
		align-items: center;
		gap: 6px;
	}

	.stats-metric {
		display: flex;
		justify-content: space-between;
		align-items: center;
		margin-bottom: 6px;
	}

	.metric-label {
		font-size: 12px;
		color: #d1d5db;
	}

	.metric-value {
		font-family: 'JetBrains Mono', ui-monospace, monospace;
		font-size: 12px;
		color: #4ade80;
	}

	.histogram-list {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}

	.histogram-row {
		display: grid;
		grid-template-columns: 60px 40px 1fr;
		gap: 8px;
		align-items: center;
		font-size: 11px;
	}

	.histogram-bar-bg {
		height: 14px;
		background: #1f2937;
		border-radius: 2px;
		overflow: hidden;
	}

	.histogram-bar {
		height: 100%;
		background: #3b82f6;
		border-radius: 2px;
		min-width: 2px;
	}

	.histogram-count {
		font-family: 'JetBrains Mono', ui-monospace, monospace;
		color: #9ca3af;
		text-align: right;
	}

	.histogram-value {
		color: #e5e7eb;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.histogram-more {
		font-size: 10px;
		color: #6b7280;
		text-align: center;
		padding-top: 6px;
		font-style: italic;
	}

	.stats-empty {
		padding: 20px;
		color: #6b7280;
		font-size: 12px;
		text-align: center;
		font-style: italic;
	}

	.stats-note {
		font-size: 10px;
		color: #6b7280;
		font-style: italic;
		margin-top: 6px;
	}

	@keyframes slideIn {
		from {
			opacity: 0;
			transform: translateX(20px);
		}
		to {
			opacity: 1;
			transform: translateX(0);
		}
	}

	.reference-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 12px 14px;
		border-bottom: 1px solid #374151;
		background: #1f2937;
	}

	.reference-title {
		font-size: 12px;
		font-weight: 600;
		color: #f3f4f6;
	}

	.reference-close {
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
	}

	.reference-close:hover {
		background: #374151;
		color: #f3f4f6;
	}

	.reference-close svg {
		width: 14px;
		height: 14px;
	}

	.reference-search {
		padding: 10px 12px;
		border-bottom: 1px solid #374151;
	}

	.search-input {
		width: 100%;
		padding: 8px 10px;
		font-size: 12px;
		background: #1f2937;
		border: 1px solid #374151;
		border-radius: 6px;
		color: #e5e7eb;
		outline: none;
	}

	.search-input:focus {
		border-color: #3b82f6;
	}

	.search-input::placeholder {
		color: #6b7280;
	}

	.reference-list {
		flex: 1;
		overflow-y: auto;
		padding: 8px 0;
	}

	.reference-category {
		margin-bottom: 4px;
	}

	.category-header {
		width: 100%;
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 8px 12px;
		border: none;
		background: transparent;
		color: #d1d5db;
		font-size: 11px;
		font-weight: 500;
		text-transform: uppercase;
		letter-spacing: 0.03em;
		cursor: pointer;
		transition: background 0.1s;
	}

	.category-header:hover {
		background: rgba(255, 255, 255, 0.05);
	}

	.expand-icon {
		width: 12px;
		height: 12px;
		flex-shrink: 0;
		transition: transform 0.15s;
		color: #6b7280;
	}

	.expand-icon.expanded {
		transform: rotate(90deg);
	}

	.category-name {
		flex: 1;
		text-align: left;
	}

	.category-count {
		font-size: 10px;
		padding: 1px 5px;
		border-radius: 8px;
		background: #374151;
		color: #9ca3af;
	}

	.category-items {
		padding: 2px 0;
	}

	.reference-item {
		width: 100%;
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 6px 12px 6px 30px;
		border: none;
		background: transparent;
		color: #e5e7eb;
		font-size: 12px;
		font-family: ui-monospace, monospace;
		text-align: left;
		cursor: pointer;
		transition: background 0.1s;
	}

	.reference-item:hover {
		background: rgba(59, 130, 246, 0.1);
	}

	.reference-item.selected {
		background: rgba(59, 130, 246, 0.2);
	}

	.item-name {
		color: #60a5fa;
	}

	.item-type {
		font-size: 10px;
		color: #6b7280;
	}

	/* Reference documentation panel */
	.reference-docs {
		flex-shrink: 0;
		border-top: 1px solid #374151;
		background: #1f2937;
		max-height: 150px;
		overflow-y: auto;
	}

	.docs-header {
		padding: 10px 12px;
		border-bottom: 1px solid #374151;
		background: #111827;
	}

	.docs-name {
		font-family: ui-monospace, monospace;
		font-size: 12px;
		color: #60a5fa;
	}

	.docs-content {
		padding: 10px 12px;
	}

	.docs-description {
		margin: 0 0 8px 0;
		font-size: 12px;
		color: #d1d5db;
		line-height: 1.5;
	}

	.docs-example {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}

	.example-label {
		font-size: 10px;
		font-weight: 500;
		text-transform: uppercase;
		color: #6b7280;
	}

	.docs-example code {
		padding: 6px 8px;
		font-family: ui-monospace, monospace;
		font-size: 11px;
		background: #111827;
		border-radius: 4px;
		color: #4ade80;
	}
</style>
