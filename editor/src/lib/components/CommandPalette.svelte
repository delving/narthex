<script lang="ts">
	import { onMount } from 'svelte';

	interface Command {
		id: string;
		label: string;
		description?: string;
		shortcut?: string[];
		category?: string;
		action: () => void;
	}

	interface Props {
		open: boolean;
		onClose: () => void;
		commands: Command[];
	}

	let { open, onClose, commands }: Props = $props();

	let searchQuery = $state('');
	let selectedIndex = $state(0);
	let inputRef: HTMLInputElement;

	// Filter commands based on search query
	const filteredCommands = $derived(() => {
		if (!searchQuery.trim()) return commands;
		const query = searchQuery.toLowerCase();
		return commands.filter(
			(cmd) =>
				cmd.label.toLowerCase().includes(query) ||
				cmd.description?.toLowerCase().includes(query) ||
				cmd.category?.toLowerCase().includes(query)
		);
	});

	// Group commands by category
	const groupedCommands = $derived(() => {
		const filtered = filteredCommands();
		const groups: Record<string, Command[]> = {};

		for (const cmd of filtered) {
			const category = cmd.category || 'Actions';
			if (!groups[category]) {
				groups[category] = [];
			}
			groups[category].push(cmd);
		}

		return groups;
	});

	// Flat list for keyboard navigation
	const flatCommands = $derived(() => filteredCommands());

	// Reset state when opening
	$effect(() => {
		if (open) {
			searchQuery = '';
			selectedIndex = 0;
			// Focus input after mount
			setTimeout(() => inputRef?.focus(), 0);
		}
	});

	// Reset selection when search changes
	$effect(() => {
		searchQuery; // dependency
		selectedIndex = 0;
	});

	function handleKeydown(e: KeyboardEvent) {
		const cmds = flatCommands();

		switch (e.key) {
			case 'ArrowDown':
				e.preventDefault();
				selectedIndex = Math.min(selectedIndex + 1, cmds.length - 1);
				scrollToSelected();
				break;
			case 'ArrowUp':
				e.preventDefault();
				selectedIndex = Math.max(selectedIndex - 1, 0);
				scrollToSelected();
				break;
			case 'Enter':
				e.preventDefault();
				if (cmds[selectedIndex]) {
					executeCommand(cmds[selectedIndex]);
				}
				break;
			case 'Escape':
				e.preventDefault();
				onClose();
				break;
		}
	}

	function scrollToSelected() {
		setTimeout(() => {
			const selected = document.querySelector('.command-item.selected');
			selected?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
		}, 0);
	}

	function executeCommand(cmd: Command) {
		onClose();
		// Execute after closing to avoid UI issues
		setTimeout(() => cmd.action(), 0);
	}

	function handleBackdropClick(e: MouseEvent) {
		if (e.target === e.currentTarget) {
			onClose();
		}
	}
</script>

{#if open}
	<div
		class="palette-backdrop"
		onclick={handleBackdropClick}
		onkeydown={handleKeydown}
		role="dialog"
		aria-modal="true"
		aria-label="Command palette"
	>
		<div class="palette-container">
			<!-- Search input -->
			<div class="search-section">
				<svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
					<circle cx="11" cy="11" r="8" />
					<path d="M21 21l-4.35-4.35" />
				</svg>
				<input
					type="text"
					class="search-input"
					placeholder="Type a command or search..."
					bind:value={searchQuery}
					bind:this={inputRef}
				/>
				<kbd class="esc-hint">ESC</kbd>
			</div>

			<!-- Commands list -->
			<div class="commands-section">
				{#if flatCommands().length === 0}
					<div class="no-results">
						No commands found for "{searchQuery}"
					</div>
				{:else}
					{#each Object.entries(groupedCommands()) as [category, cmds]}
						<div class="command-group">
							<div class="group-header">{category}</div>
							{#each cmds as cmd, i}
								{@const globalIndex = flatCommands().indexOf(cmd)}
								<button
									class="command-item"
									class:selected={globalIndex === selectedIndex}
									onclick={() => executeCommand(cmd)}
									onmouseenter={() => selectedIndex = globalIndex}
								>
									<div class="command-info">
										<span class="command-label">{cmd.label}</span>
										{#if cmd.description}
											<span class="command-description">{cmd.description}</span>
										{/if}
									</div>
									{#if cmd.shortcut}
										<div class="command-shortcut">
											{#each cmd.shortcut as key, i}
												{#if i > 0}<span class="plus">+</span>{/if}
												<kbd>{key}</kbd>
											{/each}
										</div>
									{/if}
								</button>
							{/each}
						</div>
					{/each}
				{/if}
			</div>

			<!-- Footer hint -->
			<div class="palette-footer">
				<span><kbd>↑↓</kbd> navigate</span>
				<span><kbd>Enter</kbd> select</span>
				<span><kbd>Esc</kbd> close</span>
			</div>
		</div>
	</div>
{/if}

<style>
	.palette-backdrop {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.6);
		display: flex;
		align-items: flex-start;
		justify-content: center;
		padding-top: 15vh;
		z-index: 1000;
		animation: fadeIn 0.1s ease-out;
	}

	@keyframes fadeIn {
		from { opacity: 0; }
		to { opacity: 1; }
	}

	.palette-container {
		width: 100%;
		max-width: 560px;
		background: #1f2937;
		border-radius: 12px;
		border: 1px solid #374151;
		box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5);
		overflow: hidden;
		animation: slideDown 0.15s ease-out;
	}

	@keyframes slideDown {
		from {
			opacity: 0;
			transform: translateY(-10px) scale(0.98);
		}
		to {
			opacity: 1;
			transform: translateY(0) scale(1);
		}
	}

	.search-section {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 14px 16px;
		border-bottom: 1px solid #374151;
	}

	.search-icon {
		width: 18px;
		height: 18px;
		color: #6b7280;
		flex-shrink: 0;
	}

	.search-input {
		flex: 1;
		background: transparent;
		border: none;
		outline: none;
		font-size: 15px;
		color: #f3f4f6;
	}

	.search-input::placeholder {
		color: #6b7280;
	}

	.esc-hint {
		padding: 3px 6px;
		font-size: 10px;
		font-family: ui-monospace, monospace;
		color: #6b7280;
		background: #374151;
		border-radius: 4px;
		flex-shrink: 0;
	}

	.commands-section {
		max-height: 360px;
		overflow-y: auto;
	}

	.no-results {
		padding: 24px;
		text-align: center;
		color: #6b7280;
		font-size: 14px;
	}

	.command-group {
		padding: 8px 0;
	}

	.command-group:not(:last-child) {
		border-bottom: 1px solid #374151;
	}

	.group-header {
		padding: 6px 16px;
		font-size: 11px;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: #6b7280;
	}

	.command-item {
		display: flex;
		align-items: center;
		justify-content: space-between;
		width: 100%;
		padding: 10px 16px;
		border: none;
		background: transparent;
		color: #e5e7eb;
		text-align: left;
		cursor: pointer;
		transition: background 0.1s;
	}

	.command-item:hover,
	.command-item.selected {
		background: #374151;
	}

	.command-item.selected {
		background: #3b82f6;
	}

	.command-info {
		display: flex;
		flex-direction: column;
		gap: 2px;
	}

	.command-label {
		font-size: 14px;
		font-weight: 500;
	}

	.command-description {
		font-size: 12px;
		color: #9ca3af;
	}

	.command-item.selected .command-description {
		color: rgba(255, 255, 255, 0.7);
	}

	.command-shortcut {
		display: flex;
		align-items: center;
		gap: 4px;
		flex-shrink: 0;
	}

	.command-shortcut kbd {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		min-width: 22px;
		height: 22px;
		padding: 0 6px;
		font-family: ui-monospace, monospace;
		font-size: 11px;
		color: #d1d5db;
		background: rgba(0, 0, 0, 0.3);
		border-radius: 4px;
	}

	.command-item.selected .command-shortcut kbd {
		background: rgba(255, 255, 255, 0.2);
		color: white;
	}

	.command-shortcut .plus {
		color: #6b7280;
		font-size: 10px;
	}

	.palette-footer {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 16px;
		padding: 10px 16px;
		border-top: 1px solid #374151;
		background: #111827;
	}

	.palette-footer span {
		display: flex;
		align-items: center;
		gap: 4px;
		font-size: 11px;
		color: #6b7280;
	}

	.palette-footer kbd {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		min-width: 18px;
		height: 18px;
		padding: 0 4px;
		font-family: ui-monospace, monospace;
		font-size: 10px;
		color: #9ca3af;
		background: #374151;
		border-radius: 3px;
	}
</style>
