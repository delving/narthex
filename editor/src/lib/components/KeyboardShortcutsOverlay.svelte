<script lang="ts">
	interface Props {
		open: boolean;
		onClose: () => void;
	}

	let { open, onClose }: Props = $props();

	// Close on escape
	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			e.preventDefault();
			onClose();
		}
	}

	// Close on backdrop click
	function handleBackdropClick(e: MouseEvent) {
		if (e.target === e.currentTarget) {
			onClose();
		}
	}

	const shortcutGroups = [
		{
			title: 'Global',
			shortcuts: [
				{ keys: ['Ctrl', 'K'], description: 'Open command palette' },
				{ keys: ['Ctrl', 'Shift', 'P'], description: 'Open command palette (alt)' },
				{ keys: ['?'], description: 'Show keyboard shortcuts' },
				{ keys: ['Ctrl', '1'], description: 'Focus Source Structure' },
				{ keys: ['Ctrl', '2'], description: 'Focus Target Schema' },
				{ keys: ['Ctrl', '3'], description: 'Focus Code Editor' },
				{ keys: ['Ctrl', '4'], description: 'Focus Preview' },
				{ keys: ['['], description: 'Previous record' },
				{ keys: [']'], description: 'Next record' },
				{ keys: ['Escape'], description: 'Close modal / Clear selection' }
			]
		},
		{
			title: 'Tree Navigation',
			shortcuts: [
				{ keys: ['/'], description: 'Focus search input' },
				{ keys: ['Ctrl', 'F'], description: 'Focus search input' },
				{ keys: ['↑', '↓'], description: 'Navigate up/down through nodes' },
				{ keys: ['←'], description: 'Collapse node or go to parent' },
				{ keys: ['→'], description: 'Expand node' },
				{ keys: ['Enter'], description: 'Open mapping modal for node' },
				{ keys: ['Home'], description: 'Jump to first node' },
				{ keys: ['End'], description: 'Jump to last node' }
			]
		},
		{
			title: 'Mapping Modal',
			shortcuts: [
				{ keys: ['↑', '↓'], description: 'Navigate through fields' },
				{ keys: ['←', '→'], description: 'Collapse/expand field' },
				{ keys: ['Enter'], description: 'Create mapping with selected field' },
				{ keys: ['Tab'], description: 'Switch between tabs' }
			]
		},
		{
			title: 'Code Editor',
			shortcuts: [
				{ keys: ['Ctrl', "'"], description: 'Toggle between Full Code / Tweak tabs' },
				{ keys: ['Ctrl', 'S'], description: 'Save changes' },
				{ keys: ['Ctrl', 'Z'], description: 'Undo' },
				{ keys: ['Ctrl', 'Shift', 'Z'], description: 'Redo' },
				{ keys: ['Ctrl', '/'], description: 'Toggle comment' }
			]
		},
		{
			title: 'Tweak Panel',
			shortcuts: [
				{ keys: ['Double-click'], description: 'Open mapping in editor' },
				{ keys: ['←', '→'], description: 'Navigate between records' }
			]
		}
	];
</script>

{#if open}
	<div
		class="overlay-backdrop"
		onclick={handleBackdropClick}
		onkeydown={handleKeydown}
		role="dialog"
		aria-modal="true"
		aria-label="Keyboard shortcuts"
		tabindex="-1"
	>
		<div class="overlay-content">
			<div class="overlay-header">
				<h2>Keyboard Shortcuts</h2>
				<button class="close-btn" onclick={onClose} aria-label="Close">
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
						<path d="M18 6L6 18M6 6l12 12" />
					</svg>
				</button>
			</div>

			<div class="shortcut-groups">
				{#each shortcutGroups as group}
					<div class="shortcut-group">
						<h3>{group.title}</h3>
						<div class="shortcuts">
							{#each group.shortcuts as shortcut}
								<div class="shortcut-row">
									<div class="keys">
										{#each shortcut.keys as key, i}
											{#if i > 0}<span class="plus">+</span>{/if}
											<kbd>{key}</kbd>
										{/each}
									</div>
									<div class="description">{shortcut.description}</div>
								</div>
							{/each}
						</div>
					</div>
				{/each}
			</div>

			<div class="overlay-footer">
				<span class="hint">Press <kbd>?</kbd> or <kbd>Escape</kbd> to close</span>
			</div>
		</div>
	</div>
{/if}

<style>
	.overlay-backdrop {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.75);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 1000;
		animation: fadeIn 0.15s ease-out;
	}

	@keyframes fadeIn {
		from { opacity: 0; }
		to { opacity: 1; }
	}

	.overlay-content {
		background: #1f2937;
		border-radius: 12px;
		border: 1px solid #374151;
		box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5);
		max-width: 700px;
		max-height: 85vh;
		width: 90%;
		display: flex;
		flex-direction: column;
		animation: slideUp 0.2s ease-out;
	}

	@keyframes slideUp {
		from {
			opacity: 0;
			transform: translateY(20px);
		}
		to {
			opacity: 1;
			transform: translateY(0);
		}
	}

	.overlay-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 16px 20px;
		border-bottom: 1px solid #374151;
	}

	.overlay-header h2 {
		margin: 0;
		font-size: 18px;
		font-weight: 600;
		color: #f3f4f6;
	}

	.close-btn {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 32px;
		height: 32px;
		padding: 0;
		border: none;
		background: transparent;
		color: #9ca3af;
		cursor: pointer;
		border-radius: 6px;
		transition: all 0.15s;
	}

	.close-btn:hover {
		background: #374151;
		color: #f3f4f6;
	}

	.close-btn svg {
		width: 20px;
		height: 20px;
	}

	.shortcut-groups {
		flex: 1;
		overflow-y: auto;
		padding: 16px 20px;
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
		gap: 20px;
	}

	.shortcut-group h3 {
		margin: 0 0 10px;
		font-size: 12px;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: #9ca3af;
	}

	.shortcuts {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}

	.shortcut-row {
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 6px 0;
	}

	.keys {
		display: flex;
		align-items: center;
		gap: 4px;
		min-width: 100px;
	}

	.keys kbd {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		min-width: 24px;
		height: 24px;
		padding: 0 8px;
		font-family: ui-monospace, 'SF Mono', monospace;
		font-size: 11px;
		font-weight: 500;
		color: #e5e7eb;
		background: #374151;
		border: 1px solid #4b5563;
		border-radius: 5px;
		box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
	}

	.keys .plus {
		color: #6b7280;
		font-size: 10px;
	}

	.description {
		font-size: 13px;
		color: #d1d5db;
	}

	.overlay-footer {
		padding: 12px 20px;
		border-top: 1px solid #374151;
		text-align: center;
	}

	.hint {
		font-size: 12px;
		color: #6b7280;
	}

	.hint kbd {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		min-width: 18px;
		height: 18px;
		padding: 0 5px;
		font-family: ui-monospace, 'SF Mono', monospace;
		font-size: 10px;
		color: #d1d5db;
		background: #374151;
		border-radius: 3px;
		margin: 0 2px;
	}
</style>
