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

	interface Props {
		isOpen: boolean;
		mapping: Mapping | null;
		currentRecordIndex: number;
		customCode?: string;
		customFunctions?: CustomFunction[];
		customVariables?: CustomVariable[];
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
			const initialCode = customCode ?? generateMappingLine(mapping);
			editedCode = initialCode;
			hasChanges = false;

			// Dispose old editor if exists
			if (editor) {
				editor.dispose();
				editor = null;
			}

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
				});
			}, 0);
		}
	});

	// Cleanup editor when modal closes
	$effect(() => {
		if (!isOpen && editor) {
			editor.dispose();
			editor = null;
		}
	});

	// Get the current record
	const currentRecord = $derived(sampleRecords[currentRecordIndex]);
	const totalRecords = sampleRecords.length;

	// Extract source value from the current record
	function getSourceValue(): string {
		if (!currentRecord || !mapping) return '';
		const value = getValueFromPath(currentRecord, mapping.sourcePath);
		if (value === undefined) return '';
		if (Array.isArray(value)) {
			return value.join('\n');
		}
		return value;
	}

	// Simulate the mapping output (in real app, this would execute Groovy)
	function getOutputValue(): string {
		// For now, just return the source value
		return getSourceValue();
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
			onSave(mapping.id, editedCode);
			onClose();
		}
	}

	// Navigation
	function prevRecord() {
		if (currentRecordIndex > 0) {
			onRecordChange(currentRecordIndex - 1);
		}
	}

	function nextRecord() {
		if (currentRecordIndex < totalRecords - 1) {
			onRecordChange(currentRecordIndex + 1);
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
					<span class="mapping-info">
						<span class="source">{mapping.sourceName}</span>
						<span class="arrow">→</span>
						<span class="target">{mapping.targetName}</span>
					</span>
				</div>
				<div class="header-right">
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

			<!-- Content wrapper for main + reference panel -->
			<div class="modal-body" class:panel-open={referencePanelOpen}>
				<!-- Main Content -->
				<div class="modal-content">
					<!-- Code editor section -->
					<div class="code-section">
						<div class="section-header">
							<span class="section-title">Groovy Code</span>
							<div class="section-actions">
								{#if hasChanges}
									<span class="modified-badge">Modified</span>
								{/if}
								<button class="action-btn" onclick={handleReset}>Reset to Default</button>
							</div>
						</div>
						<div class="code-editor" bind:this={editorContainer}></div>
					</div>

					<!-- Preview section -->
					<div class="preview-section">
						<!-- Record navigation -->
						<div class="record-nav">
							<button
								class="nav-btn"
								onclick={prevRecord}
								disabled={currentRecordIndex === 0}
								aria-label="Previous record"
							>
								<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
									<path d="M15 19l-7-7 7-7" />
								</svg>
							</button>
							<span class="record-info">
								Record <strong>{currentRecordIndex + 1}</strong> of <strong>{totalRecords}</strong>
							</span>
							<button
								class="nav-btn"
								onclick={nextRecord}
								disabled={currentRecordIndex === totalRecords - 1}
								aria-label="Next record"
							>
								<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
									<path d="M9 5l7 7-7 7" />
								</svg>
							</button>
						</div>

						<!-- Input/Output preview -->
						<div class="value-preview">
							<div class="value-panel">
								<div class="value-header">
									<span class="value-label">Input</span>
									<span class="value-path">{sourcePathToGroovy(mapping.sourcePath)}</span>
								</div>
								<div class="value-content">
									<pre>{getSourceValue() || '(empty)'}</pre>
								</div>
							</div>

							<div class="arrow-divider">
								<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
									<path d="M5 12h14M12 5l7 7-7 7" />
								</svg>
							</div>

							<div class="value-panel">
								<div class="value-header">
									<span class="value-label">Output</span>
									<span class="value-path">{mapping.targetName}</span>
								</div>
								<div class="value-content">
									<pre>{getOutputValue() || '(empty)'}</pre>
								</div>
							</div>
						</div>
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
		height: 70vh;
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

	.mapping-info {
		display: flex;
		align-items: center;
		gap: 6px;
		font-size: 13px;
		font-family: ui-monospace, monospace;
	}

	.mapping-info .source {
		color: #60a5fa;
	}

	.mapping-info .arrow {
		color: #6b7280;
	}

	.mapping-info .target {
		color: #4ade80;
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
		flex: 1;
		min-height: 250px;
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
		flex: 0 0 auto;
		display: flex;
		flex-direction: column;
		min-height: 180px;
		max-height: 220px;
		padding: 12px 16px;
		gap: 10px;
	}

	.record-nav {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 12px;
	}

	.nav-btn {
		width: 32px;
		height: 32px;
		display: flex;
		align-items: center;
		justify-content: center;
		background: #374151;
		border: none;
		border-radius: 6px;
		color: #d1d5db;
		cursor: pointer;
		transition: background 0.15s;
	}

	.nav-btn:hover:not(:disabled) {
		background: #4b5563;
	}

	.nav-btn:disabled {
		opacity: 0.4;
		cursor: not-allowed;
	}

	.nav-btn svg {
		width: 18px;
		height: 18px;
	}

	.record-info {
		font-size: 13px;
		color: #9ca3af;
	}

	.record-info strong {
		color: #f3f4f6;
	}

	.value-preview {
		flex: 1;
		display: flex;
		gap: 16px;
		min-height: 0;
	}

	.value-panel {
		flex: 1;
		display: flex;
		flex-direction: column;
		background: #111827;
		border-radius: 8px;
		overflow: hidden;
		min-height: 0;
	}

	.value-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 10px 14px;
		background: #1f2937;
		border-bottom: 1px solid #374151;
	}

	.value-label {
		font-size: 11px;
		font-weight: 500;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: #9ca3af;
	}

	.value-path {
		font-family: ui-monospace, monospace;
		font-size: 11px;
		color: #6b7280;
	}

	.value-content {
		flex: 1;
		padding: 14px;
		overflow: auto;
		min-height: 0;
	}

	.value-content pre {
		margin: 0;
		font-family: 'JetBrains Mono', ui-monospace, monospace;
		font-size: 13px;
		color: #e5e7eb;
		white-space: pre-wrap;
		word-break: break-word;
	}

	.arrow-divider {
		display: flex;
		align-items: center;
		justify-content: center;
		color: #4b5563;
	}

	.arrow-divider svg {
		width: 24px;
		height: 24px;
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
