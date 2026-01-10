<script lang="ts">
	import { mappingsStore, type Mapping } from '$lib/stores/mappingStore';
	import { sampleRecords, getValueFromPath } from '$lib/sampleData';
	import { sourcePathToGroovy, generateMappingLine } from '$lib/utils/codeGenerator';
	import { onMount } from 'svelte';

	interface Props {
		currentRecordIndex?: number;
		onRecordChange?: (index: number) => void;
		selectedMappingId?: string | null;
		onMappingSelect?: (id: string) => void;
		onEditClick?: (mapping: Mapping) => void;
	}

	let { currentRecordIndex = 0, onRecordChange, selectedMappingId: propSelectedId = null, onMappingSelect, onEditClick }: Props = $props();

	// Track mappings from store
	let mappings = $state<Mapping[]>([]);

	// Currently selected mapping - use prop if provided, otherwise local state
	let localSelectedId = $state<string | null>(null);
	const selectedMappingId = $derived(propSelectedId ?? localSelectedId);

	// Update selection
	function selectMapping(id: string) {
		if (onMappingSelect) {
			onMappingSelect(id);
		} else {
			localSelectedId = id;
		}
	}

	// Custom code for the selected mapping (allows user edits)
	let customCode = $state<Record<string, string>>({});

	// Subscribe to mappings store
	onMount(() => {
		const unsubscribe = mappingsStore.subscribe((m) => {
			mappings = m;
			// Auto-select first mapping if none selected
			if (m.length > 0 && !selectedMappingId) {
				selectMapping(m[0].id);
			}
		});
		return unsubscribe;
	});

	// Get the selected mapping
	const selectedMapping = $derived(
		mappings.find((m) => m.id === selectedMappingId) || null
	);

	// Get the code for the selected mapping
	const mappingCode = $derived(() => {
		if (!selectedMapping) return '';
		// Use custom code if available, otherwise generate
		return customCode[selectedMapping.id] ?? generateMappingLine(selectedMapping);
	});

	// Get the current record
	const currentRecord = $derived(sampleRecords[currentRecordIndex]);

	// Extract source value from the current record
	function getSourceValue(mapping: Mapping): string {
		if (!currentRecord) return '';

		const value = getValueFromPath(currentRecord, mapping.sourcePath);
		if (value === undefined) return '';
		if (Array.isArray(value)) {
			return value.join('\n');
		}
		return value;
	}

	// Simulate the mapping output (in real app, this would execute Groovy)
	function getOutputValue(mapping: Mapping): string {
		const sourceValue = getSourceValue(mapping);
		// For now, just return the source value (text() equivalent)
		return sourceValue;
	}

	// Handle code change for a mapping
	function handleCodeChange(mappingId: string, newCode: string) {
		customCode = { ...customCode, [mappingId]: newCode };
	}

	// Reset code to generated version
	function resetCode(mapping: Mapping) {
		const { [mapping.id]: _, ...rest } = customCode;
		customCode = rest;
	}

	// Check if code has been customized
	function isCustomized(mapping: Mapping): boolean {
		return mapping.id in customCode;
	}

	// Navigation
	function prevRecord() {
		if (currentRecordIndex > 0) {
			onRecordChange?.(currentRecordIndex - 1);
		}
	}

	function nextRecord() {
		if (currentRecordIndex < sampleRecords.length - 1) {
			onRecordChange?.(currentRecordIndex + 1);
		}
	}
</script>

<div class="tweak-panel">
	{#if mappings.length === 0}
		<div class="empty-state">
			<p>No mappings defined yet.</p>
			<p class="hint">Drag fields from the source tree to the target tree to create mappings.</p>
		</div>
	{:else}
		<div class="tweak-layout">
			<!-- Mapping list sidebar -->
			<div class="mapping-list">
				<div class="list-header">Mappings</div>
				<div class="list-content">
					{#each mappings as mapping (mapping.id)}
						<button
							class="mapping-item"
							class:selected={selectedMappingId === mapping.id}
							class:customized={isCustomized(mapping)}
							onclick={() => selectMapping(mapping.id)}
						>
							<span class="source-name">{mapping.sourceName}</span>
							<span class="arrow">→</span>
							<span class="target-name">{mapping.targetName}</span>
							{#if isCustomized(mapping)}
								<span class="custom-badge">edited</span>
							{/if}
						</button>
					{/each}
				</div>
			</div>

			<!-- Main tweak area -->
			<div class="tweak-main">
				{#if selectedMapping}
					<!-- Code editor for this mapping -->
					<div class="code-section">
						<div class="section-header">
							<span class="section-title">Groovy Code</span>
							<div class="section-actions">
								{#if isCustomized(selectedMapping)}
									<button class="reset-btn" onclick={() => resetCode(selectedMapping)}>
										Reset
									</button>
								{/if}
								{#if onEditClick}
									<button class="edit-btn" onclick={() => onEditClick(selectedMapping)} title="Open in editor">
										<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
											<path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
											<path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
										</svg>
										Edit
									</button>
								{/if}
							</div>
						</div>
						<div class="code-input-wrapper">
							<input
								type="text"
								class="code-input"
								value={mappingCode()}
								oninput={(e) => handleCodeChange(selectedMapping.id, e.currentTarget.value)}
								spellcheck="false"
							/>
						</div>
					</div>

					<!-- Record navigation -->
					<div class="record-nav">
						<button
							class="nav-btn"
							onclick={prevRecord}
							disabled={currentRecordIndex === 0}
						>
							<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
								<path d="M15 19l-7-7 7-7" />
							</svg>
						</button>
						<span class="record-info">
							Record <strong>{currentRecordIndex + 1}</strong> of <strong>{sampleRecords.length}</strong>
						</span>
						<button
							class="nav-btn"
							onclick={nextRecord}
							disabled={currentRecordIndex === sampleRecords.length - 1}
						>
							<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
								<path d="M9 5l7 7-7 7" />
							</svg>
						</button>
					</div>

					<!-- Value preview -->
					<div class="value-preview">
						<div class="value-column">
							<div class="value-header">
								<span class="value-label">Input</span>
								<span class="value-path">{sourcePathToGroovy(selectedMapping.sourcePath)}</span>
							</div>
							<div class="value-content">
								<pre>{getSourceValue(selectedMapping) || '(empty)'}</pre>
							</div>
						</div>
						<div class="arrow-column">
							<span class="transform-arrow">→</span>
						</div>
						<div class="value-column">
							<div class="value-header">
								<span class="value-label">Output</span>
								<span class="value-path">{selectedMapping.targetName}</span>
							</div>
							<div class="value-content">
								<pre>{getOutputValue(selectedMapping) || '(empty)'}</pre>
							</div>
						</div>
					</div>
				{:else}
					<div class="empty-state">
						<p>Select a mapping to tweak</p>
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
		width: 200px;
		border-right: 1px solid #374151;
		display: flex;
		flex-direction: column;
	}

	.list-header {
		padding: 8px 12px;
		font-size: 11px;
		font-weight: 500;
		text-transform: uppercase;
		color: #9ca3af;
		background: #1f2937;
		border-bottom: 1px solid #374151;
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
		padding: 8px;
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
	}

	.mapping-item .arrow {
		color: #4b5563;
	}

	.mapping-item .target-name {
		color: #4ade80;
	}

	.mapping-item .custom-badge {
		margin-left: auto;
		font-size: 9px;
		padding: 1px 4px;
		border-radius: 3px;
		background: #854d0e;
		color: #fcd34d;
	}

	/* Main tweak area */
	.tweak-main {
		flex: 1;
		display: flex;
		flex-direction: column;
		padding: 12px;
		gap: 12px;
		overflow-y: auto;
	}

	/* Code section */
	.code-section {
		background: #1f2937;
		border-radius: 6px;
		overflow: hidden;
	}

	.section-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 8px 12px;
		background: #374151;
	}

	.section-title {
		font-size: 11px;
		font-weight: 500;
		text-transform: uppercase;
		color: #9ca3af;
	}

	.section-actions {
		display: flex;
		align-items: center;
		gap: 8px;
	}

	.reset-btn {
		padding: 2px 8px;
		font-size: 10px;
		background: #4b5563;
		border: none;
		border-radius: 3px;
		color: #d1d5db;
		cursor: pointer;
	}

	.reset-btn:hover {
		background: #6b7280;
	}

	.edit-btn {
		display: flex;
		align-items: center;
		gap: 4px;
		padding: 4px 10px;
		font-size: 11px;
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
		width: 12px;
		height: 12px;
	}

	.code-input-wrapper {
		padding: 8px 12px;
	}

	.code-input {
		width: 100%;
		padding: 8px 12px;
		font-family: 'JetBrains Mono', 'Fira Code', monospace;
		font-size: 13px;
		background: #0a0a0a;
		border: 1px solid #374151;
		border-radius: 4px;
		color: #e5e7eb;
		outline: none;
	}

	.code-input:focus {
		border-color: #3b82f6;
	}

	/* Record navigation */
	.record-nav {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 12px;
		padding: 8px;
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
	}

	.nav-btn:hover:not(:disabled) {
		background: #4b5563;
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

	/* Value preview */
	.value-preview {
		flex: 1;
		display: flex;
		gap: 12px;
		min-height: 100px;
	}

	.value-column {
		flex: 1;
		display: flex;
		flex-direction: column;
		background: #1f2937;
		border-radius: 6px;
		overflow: hidden;
	}

	.value-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 8px 12px;
		background: #374151;
	}

	.value-label {
		font-size: 11px;
		font-weight: 500;
		text-transform: uppercase;
		color: #9ca3af;
	}

	.value-path {
		font-family: 'JetBrains Mono', monospace;
		font-size: 10px;
		color: #6b7280;
	}

	.value-content {
		flex: 1;
		padding: 12px;
		overflow: auto;
	}

	.value-content pre {
		margin: 0;
		font-family: 'JetBrains Mono', monospace;
		font-size: 13px;
		color: #e5e7eb;
		white-space: pre-wrap;
		word-break: break-word;
	}

	.arrow-column {
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 0 8px;
	}

	.transform-arrow {
		font-size: 24px;
		color: #4b5563;
	}
</style>
