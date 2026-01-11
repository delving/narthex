<script lang="ts">
	import { loadDatasets, type DatasetInfo } from '$lib/api/mappingEditor';
	import { onMount } from 'svelte';

	interface Props {
		selectedSpec: string | null;
		onSelect: (spec: string) => void;
	}

	let { selectedSpec, onSelect }: Props = $props();

	let datasets = $state<DatasetInfo[]>([]);
	let isLoading = $state(false);
	let error = $state<string | null>(null);
	let isOpen = $state(false);
	let searchQuery = $state('');

	// Filter datasets by search query
	const filteredDatasets = $derived(
		datasets.filter(ds =>
			ds.spec.toLowerCase().includes(searchQuery.toLowerCase()) ||
			(ds.name && ds.name.toLowerCase().includes(searchQuery.toLowerCase()))
		)
	);

	// Get display name for selected dataset
	const selectedName = $derived(
		selectedSpec
			? (datasets.find(ds => ds.spec === selectedSpec)?.name || selectedSpec)
			: 'Sample Data (Demo)'
	);

	async function loadDatasetList() {
		isLoading = true;
		error = null;
		try {
			datasets = await loadDatasets();
		} catch (err) {
			error = err instanceof Error ? err.message : 'Failed to load datasets';
			console.error('Failed to load datasets:', err);
		} finally {
			isLoading = false;
		}
	}

	function handleSelect(spec: string) {
		onSelect(spec);
		isOpen = false;
		searchQuery = '';
	}

	function handleClickOutside(e: MouseEvent) {
		const target = e.target as HTMLElement;
		if (!target.closest('.dataset-picker')) {
			isOpen = false;
		}
	}

	onMount(() => {
		loadDatasetList();
		document.addEventListener('click', handleClickOutside);
		return () => document.removeEventListener('click', handleClickOutside);
	});
</script>

<div class="dataset-picker relative">
	<button
		class="picker-button"
		onclick={() => isOpen = !isOpen}
		disabled={isLoading}
	>
		{#if isLoading}
			<svg class="animate-spin h-4 w-4" viewBox="0 0 24 24">
				<circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" fill="none"/>
				<path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/>
			</svg>
		{:else}
			<span class="truncate max-w-[200px]">{selectedName}</span>
			<svg class="w-4 h-4 flex-shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
				<path d="M6 9l6 6 6-6"/>
			</svg>
		{/if}
	</button>

	{#if isOpen && !isLoading}
		<div class="dropdown">
			<div class="search-container">
				<input
					type="text"
					class="search-input"
					placeholder="Search datasets..."
					bind:value={searchQuery}
				/>
			</div>

			<!-- Sample data option -->
			<button
				class="dataset-item sample-option"
				class:selected={!selectedSpec}
				onclick={() => handleSelect('')}
			>
				<div class="dataset-info">
					<span class="dataset-name">Sample Data (Demo)</span>
					<span class="dataset-spec">Use built-in sample data</span>
				</div>
				<span class="sample-badge">DEMO</span>
			</button>

			<div class="divider"></div>

			{#if error}
				<div class="error-message">
					{error}
					<button class="retry-btn" onclick={loadDatasetList}>Retry</button>
				</div>
			{:else if filteredDatasets.length === 0}
				<div class="no-results">
					{searchQuery ? `No datasets matching "${searchQuery}"` : 'No datasets available'}
				</div>
			{:else}
				<div class="dataset-list">
					{#each filteredDatasets as ds (ds.spec)}
						<button
							class="dataset-item"
							class:selected={ds.spec === selectedSpec}
							onclick={() => handleSelect(ds.spec)}
						>
							<div class="dataset-info">
								<span class="dataset-name">{ds.name || ds.spec}</span>
								<span class="dataset-spec">{ds.spec}</span>
							</div>
							{#if ds.sourceRecordCount}
								<span class="record-count">{ds.sourceRecordCount.toLocaleString()} records</span>
							{/if}
						</button>
					{/each}
				</div>
			{/if}
		</div>
	{/if}
</div>

<style>
	.dataset-picker {
		position: relative;
	}

	.picker-button {
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 6px 12px;
		background: #374151;
		border: 1px solid #4b5563;
		border-radius: 6px;
		color: #e5e7eb;
		font-size: 13px;
		cursor: pointer;
		transition: all 0.15s;
		min-width: 150px;
	}

	.picker-button:hover:not(:disabled) {
		background: #4b5563;
		border-color: #6b7280;
	}

	.picker-button:disabled {
		opacity: 0.7;
		cursor: wait;
	}

	.dropdown {
		position: absolute;
		top: 100%;
		left: 0;
		right: 0;
		margin-top: 4px;
		background: #1f2937;
		border: 1px solid #374151;
		border-radius: 8px;
		box-shadow: 0 10px 25px rgba(0, 0, 0, 0.3);
		z-index: 100;
		min-width: 280px;
		max-height: 400px;
		display: flex;
		flex-direction: column;
	}

	.search-container {
		padding: 8px;
		border-bottom: 1px solid #374151;
	}

	.search-input {
		width: 100%;
		padding: 8px 12px;
		background: #111827;
		border: 1px solid #374151;
		border-radius: 4px;
		color: #e5e7eb;
		font-size: 13px;
		outline: none;
	}

	.search-input:focus {
		border-color: #3b82f6;
	}

	.search-input::placeholder {
		color: #6b7280;
	}

	.dataset-list {
		overflow-y: auto;
		max-height: 300px;
	}

	.dataset-item {
		width: 100%;
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 10px 12px;
		background: transparent;
		border: none;
		color: #d1d5db;
		text-align: left;
		cursor: pointer;
		transition: background 0.1s;
	}

	.dataset-item:hover {
		background: #374151;
	}

	.dataset-item.selected {
		background: #1e3a5f;
	}

	.dataset-info {
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}

	.dataset-name {
		font-size: 13px;
		font-weight: 500;
		color: #f3f4f6;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.dataset-spec {
		font-size: 11px;
		color: #6b7280;
		font-family: ui-monospace, monospace;
	}

	.record-count {
		font-size: 11px;
		color: #9ca3af;
		flex-shrink: 0;
	}

	.error-message {
		padding: 12px;
		color: #f87171;
		font-size: 12px;
		text-align: center;
	}

	.retry-btn {
		margin-left: 8px;
		padding: 2px 8px;
		background: #374151;
		border: none;
		border-radius: 4px;
		color: #d1d5db;
		font-size: 11px;
		cursor: pointer;
	}

	.retry-btn:hover {
		background: #4b5563;
	}

	.no-results {
		padding: 16px;
		text-align: center;
		color: #6b7280;
		font-size: 13px;
	}

	.sample-option {
		background: #1e3a5f20;
	}

	.sample-badge {
		font-size: 10px;
		padding: 2px 6px;
		background: #854d0e;
		color: #fcd34d;
		border-radius: 4px;
		font-weight: 600;
	}

	.divider {
		height: 1px;
		background: #374151;
		margin: 4px 0;
	}
</style>
