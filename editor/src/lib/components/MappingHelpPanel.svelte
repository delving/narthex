<script lang="ts">
	import {
		helpSections,
		commonPatterns,
		getTipsForFieldType,
		searchHelp,
		type HelpSection
	} from '$lib/data/mappingHelp';

	interface Props {
		fieldType?: string; // If provided, shows tips for this field type
		compact?: boolean; // Compact mode for smaller panels
	}

	let { fieldType, compact = false }: Props = $props();

	// Search state
	let searchQuery = $state('');
	let expandedSections = $state<Set<string>>(new Set(['node-navigation', 'string-functions']));

	// Filtered sections
	const filteredSections = $derived(searchHelp(searchQuery));

	// Tips for current field type
	const currentTips = $derived(fieldType ? getTipsForFieldType(fieldType) : []);

	// Toggle section
	function toggleSection(id: string) {
		const newSet = new Set(expandedSections);
		if (newSet.has(id)) {
			newSet.delete(id);
		} else {
			newSet.add(id);
		}
		expandedSections = newSet;
	}

	// When searching, expand all matching sections
	$effect(() => {
		if (searchQuery) {
			expandedSections = new Set(filteredSections.map(s => s.id));
		}
	});

	// Simple markdown to HTML converter for code blocks
	function renderMarkdown(content: string): string {
		// Escape HTML first
		let html = content
			.replace(/&/g, '&amp;')
			.replace(/</g, '&lt;')
			.replace(/>/g, '&gt;');

		// Code blocks
		html = html.replace(/```groovy\n([\s\S]*?)```/g, '<pre class="code-block"><code>$1</code></pre>');
		html = html.replace(/```\n([\s\S]*?)```/g, '<pre class="code-block"><code>$1</code></pre>');

		// Inline code
		html = html.replace(/`([^`]+)`/g, '<code class="inline-code">$1</code>');

		// Bold
		html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');

		// Headers
		html = html.replace(/^### (.+)$/gm, '<h4 class="help-h4">$1</h4>');
		html = html.replace(/^## (.+)$/gm, '<h3 class="help-h3">$1</h3>');

		// Lists
		html = html.replace(/^- (.+)$/gm, '<li>$1</li>');
		html = html.replace(/(<li>.*<\/li>\n?)+/g, '<ul class="help-list">$&</ul>');

		// Paragraphs (simple)
		html = html.replace(/\n\n/g, '</p><p>');

		return html;
	}
</script>

<div class="help-panel" class:compact>
	<!-- Search -->
	<div class="help-search">
		<svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
			<circle cx="11" cy="11" r="8" />
			<path d="M21 21l-4.35-4.35" />
		</svg>
		<input
			type="text"
			class="search-input"
			placeholder="Search help..."
			bind:value={searchQuery}
		/>
	</div>

	<div class="help-content">
		<!-- Quick Tips (if field type provided) -->
		{#if currentTips.length > 0 && !searchQuery}
			<div class="tips-section">
				<h4 class="tips-title">
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
						<circle cx="12" cy="12" r="10" />
						<path d="M12 16v-4M12 8h.01" />
					</svg>
					Tips for {fieldType} fields
				</h4>
				<ul class="tips-list">
					{#each currentTips as tip}
						<li>{@html tip.replace(/`([^`]+)`/g, '<code>$1</code>')}</li>
					{/each}
				</ul>
			</div>
		{/if}

		<!-- Common Patterns (if not searching) -->
		{#if !searchQuery && !compact}
			<div class="patterns-section">
				<h4 class="section-title">Common Patterns</h4>
				<div class="patterns-grid">
					{#each commonPatterns.slice(0, compact ? 4 : 8) as pattern}
						<div class="pattern-card">
							<div class="pattern-name">{pattern.name}</div>
							<code class="pattern-code">{pattern.code}</code>
							<div class="pattern-desc">{pattern.description}</div>
						</div>
					{/each}
				</div>
			</div>
		{/if}

		<!-- Help Sections -->
		<div class="sections">
			<h4 class="section-title">
				{searchQuery ? `Results for "${searchQuery}"` : 'Reference Guide'}
			</h4>
			{#if filteredSections.length === 0}
				<div class="no-results">No matching help topics found</div>
			{:else}
				{#each filteredSections as section (section.id)}
					<div class="help-section">
						<button
							class="section-header"
							onclick={() => toggleSection(section.id)}
						>
							<svg
								class="expand-icon"
								class:expanded={expandedSections.has(section.id)}
								viewBox="0 0 24 24"
								fill="none"
								stroke="currentColor"
								stroke-width="2"
							>
								<path d="M9 5l7 7-7 7" />
							</svg>
							<span class="section-name">{section.title}</span>
						</button>
						{#if expandedSections.has(section.id)}
							<div class="section-content">
								{@html renderMarkdown(section.content)}
							</div>
						{/if}
					</div>
				{/each}
			{/if}
		</div>
	</div>
</div>

<style>
	.help-panel {
		display: flex;
		flex-direction: column;
		height: 100%;
		background: #111827;
		color: #e5e7eb;
	}

	.help-panel.compact {
		font-size: 12px;
	}

	.help-search {
		position: relative;
		padding: 12px;
		border-bottom: 1px solid #374151;
		flex-shrink: 0;
	}

	.search-icon {
		position: absolute;
		left: 22px;
		top: 50%;
		transform: translateY(-50%);
		width: 14px;
		height: 14px;
		color: #6b7280;
	}

	.search-input {
		width: 100%;
		padding: 8px 12px 8px 32px;
		font-size: 13px;
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

	.help-content {
		flex: 1;
		overflow-y: auto;
		padding: 12px;
	}

	/* Tips section */
	.tips-section {
		background: rgba(59, 130, 246, 0.1);
		border: 1px solid rgba(59, 130, 246, 0.3);
		border-radius: 8px;
		padding: 12px;
		margin-bottom: 16px;
	}

	.tips-title {
		display: flex;
		align-items: center;
		gap: 8px;
		margin: 0 0 10px 0;
		font-size: 13px;
		font-weight: 600;
		color: #60a5fa;
	}

	.tips-title svg {
		width: 16px;
		height: 16px;
	}

	.tips-list {
		margin: 0;
		padding: 0 0 0 20px;
		font-size: 12px;
		line-height: 1.6;
		color: #d1d5db;
	}

	.tips-list li {
		margin-bottom: 4px;
	}

	.tips-list :global(code) {
		padding: 1px 4px;
		background: rgba(0, 0, 0, 0.3);
		border-radius: 3px;
		font-family: ui-monospace, monospace;
		font-size: 11px;
		color: #4ade80;
	}

	/* Common patterns */
	.patterns-section {
		margin-bottom: 16px;
	}

	.section-title {
		margin: 0 0 10px 0;
		font-size: 11px;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: #9ca3af;
	}

	.patterns-grid {
		display: grid;
		grid-template-columns: 1fr;
		gap: 8px;
	}

	.pattern-card {
		background: #1f2937;
		border-radius: 6px;
		padding: 10px 12px;
	}

	.pattern-name {
		font-size: 12px;
		font-weight: 500;
		color: #f3f4f6;
		margin-bottom: 4px;
	}

	.pattern-code {
		display: block;
		padding: 6px 8px;
		margin: 4px 0;
		background: #0d1117;
		border-radius: 4px;
		font-family: ui-monospace, monospace;
		font-size: 11px;
		color: #4ade80;
		white-space: nowrap;
		overflow-x: auto;
	}

	.pattern-desc {
		font-size: 11px;
		color: #9ca3af;
	}

	/* Help sections */
	.sections {
		/* margin-top: 8px; */
	}

	.help-section {
		margin-bottom: 4px;
	}

	.section-header {
		width: 100%;
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 8px 10px;
		border: none;
		background: #1f2937;
		border-radius: 6px;
		color: #e5e7eb;
		font-size: 13px;
		font-weight: 500;
		cursor: pointer;
		transition: background 0.1s;
	}

	.section-header:hover {
		background: #374151;
	}

	.expand-icon {
		width: 14px;
		height: 14px;
		flex-shrink: 0;
		transition: transform 0.15s;
		color: #6b7280;
	}

	.expand-icon.expanded {
		transform: rotate(90deg);
	}

	.section-name {
		flex: 1;
		text-align: left;
	}

	.section-content {
		padding: 12px 12px 12px 32px;
		font-size: 13px;
		line-height: 1.6;
	}

	.no-results {
		padding: 24px;
		text-align: center;
		color: #6b7280;
		font-size: 13px;
	}

	/* Markdown rendering styles */
	.section-content :global(h3.help-h3) {
		margin: 16px 0 8px 0;
		font-size: 14px;
		font-weight: 600;
		color: #f3f4f6;
	}

	.section-content :global(h4.help-h4) {
		margin: 12px 0 6px 0;
		font-size: 13px;
		font-weight: 600;
		color: #d1d5db;
	}

	.section-content :global(pre.code-block) {
		margin: 8px 0;
		padding: 10px 12px;
		background: #0d1117;
		border-radius: 6px;
		overflow-x: auto;
	}

	.section-content :global(pre.code-block code) {
		font-family: 'JetBrains Mono', ui-monospace, monospace;
		font-size: 12px;
		color: #4ade80;
		line-height: 1.5;
	}

	.section-content :global(code.inline-code) {
		padding: 2px 5px;
		background: rgba(0, 0, 0, 0.4);
		border-radius: 4px;
		font-family: ui-monospace, monospace;
		font-size: 12px;
		color: #60a5fa;
	}

	.section-content :global(ul.help-list) {
		margin: 8px 0;
		padding: 0 0 0 20px;
	}

	.section-content :global(ul.help-list li) {
		margin-bottom: 4px;
	}

	.section-content :global(strong) {
		color: #f3f4f6;
	}

	.section-content :global(p) {
		margin: 8px 0;
	}
</style>
