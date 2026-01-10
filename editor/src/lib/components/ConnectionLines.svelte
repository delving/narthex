<script lang="ts">
	import type { Connection } from '$lib/types';
	import { onMount } from 'svelte';

	interface Props {
		connections: Connection[];
		sourceContainerId: string;
		targetContainerId: string;
		selectedConnectionId?: string | null;
		onSelectConnection?: (connection: Connection | null) => void;
	}

	let {
		connections,
		sourceContainerId,
		targetContainerId,
		selectedConnectionId = null,
		onSelectConnection
	}: Props = $props();

	let svgElement: SVGSVGElement;
	let lines = $state<
		Array<{
			connection: Connection;
			sourceY: number;
			targetY: number;
		}>
	>([]);

	function updateLines() {
		if (!svgElement) return;

		const svgRect = svgElement.getBoundingClientRect();
		const sourceContainer = document.getElementById(sourceContainerId);
		const targetContainer = document.getElementById(targetContainerId);

		if (!sourceContainer || !targetContainer) return;

		const newLines: typeof lines = [];

		for (const connection of connections) {
			// Find source element by data attribute
			const sourceEl = sourceContainer.querySelector(
				`[data-node-id="${connection.sourceId}"]`
			);
			const targetEl = targetContainer.querySelector(
				`[data-node-id="${connection.targetId}"]`
			);

			if (sourceEl && targetEl) {
				const sourceRect = sourceEl.getBoundingClientRect();
				const targetRect = targetEl.getBoundingClientRect();

				newLines.push({
					connection,
					sourceY: sourceRect.top + sourceRect.height / 2 - svgRect.top,
					targetY: targetRect.top + targetRect.height / 2 - svgRect.top
				});
			}
		}

		lines = newLines;
	}

	function handleClick(connection: Connection) {
		onSelectConnection?.(
			selectedConnectionId === connection.id ? null : connection
		);
	}

	onMount(() => {
		updateLines();

		// Update on scroll or resize
		const sourceContainer = document.getElementById(sourceContainerId);
		const targetContainer = document.getElementById(targetContainerId);

		const observer = new ResizeObserver(() => {
			requestAnimationFrame(updateLines);
		});

		if (svgElement) observer.observe(svgElement);
		if (sourceContainer) {
			observer.observe(sourceContainer);
			sourceContainer.addEventListener('scroll', updateLines);
		}
		if (targetContainer) {
			observer.observe(targetContainer);
			targetContainer.addEventListener('scroll', updateLines);
		}

		// Periodic update to catch tree expand/collapse
		const interval = setInterval(updateLines, 500);

		return () => {
			observer.disconnect();
			sourceContainer?.removeEventListener('scroll', updateLines);
			targetContainer?.removeEventListener('scroll', updateLines);
			clearInterval(interval);
		};
	});

	// Recalculate when connections change
	$effect(() => {
		connections;
		requestAnimationFrame(updateLines);
	});
</script>

<svg bind:this={svgElement} class="connection-svg">
	<defs>
		<linearGradient id="lineGradient" x1="0%" y1="0%" x2="100%" y2="0%">
			<stop offset="0%" stop-color="#60a5fa" />
			<stop offset="100%" stop-color="#4ade80" />
		</linearGradient>
		<linearGradient id="lineGradientSelected" x1="0%" y1="0%" x2="100%" y2="0%">
			<stop offset="0%" stop-color="#3b82f6" />
			<stop offset="100%" stop-color="#22c55e" />
		</linearGradient>
	</defs>

	{#each lines as line (line.connection.id)}
		{@const isSelected = selectedConnectionId === line.connection.id}
		{@const width = svgElement?.clientWidth ?? 200}
		{@const cp1x = width * 0.3}
		{@const cp2x = width * 0.7}

		<!-- Hit area (invisible, wider for easier clicking) -->
		<path
			d="M 0 {line.sourceY} C {cp1x} {line.sourceY}, {cp2x} {line.targetY}, {width} {line.targetY}"
			fill="none"
			stroke="transparent"
			stroke-width="20"
			class="connection-hit-area"
			onclick={() => handleClick(line.connection)}
			role="button"
			tabindex="0"
			onkeydown={(e) => {
				if (e.key === 'Enter') handleClick(line.connection);
			}}
		/>

		<!-- Visible line -->
		<path
			d="M 0 {line.sourceY} C {cp1x} {line.sourceY}, {cp2x} {line.targetY}, {width} {line.targetY}"
			fill="none"
			stroke={isSelected ? 'url(#lineGradientSelected)' : 'url(#lineGradient)'}
			stroke-width={isSelected ? 3 : 2}
			stroke-opacity={isSelected ? 1 : 0.6}
			class="connection-line"
			class:selected={isSelected}
		/>

		<!-- Source endpoint -->
		<circle
			cx="0"
			cy={line.sourceY}
			r={isSelected ? 5 : 4}
			fill={isSelected ? '#3b82f6' : '#60a5fa'}
			class="endpoint"
		/>

		<!-- Target endpoint -->
		<circle
			cx={width}
			cy={line.targetY}
			r={isSelected ? 5 : 4}
			fill={isSelected ? '#22c55e' : '#4ade80'}
			class="endpoint"
		/>
	{/each}

	{#if lines.length === 0}
		<text x="50%" y="50%" text-anchor="middle" fill="#4b5563" font-size="12">
			No connections visible
		</text>
	{/if}
</svg>

<style>
	.connection-svg {
		width: 100%;
		height: 100%;
		overflow: visible;
	}

	.connection-hit-area {
		cursor: pointer;
	}

	.connection-line {
		pointer-events: none;
		transition:
			stroke-width 0.15s,
			stroke-opacity 0.15s;
	}

	.connection-line.selected {
		filter: drop-shadow(0 0 4px rgba(59, 130, 246, 0.5));
	}

	.endpoint {
		transition: r 0.15s;
	}
</style>
