<script lang="ts">
	import { themeStore, type ThemeMode } from '$lib/stores/theme.svelte';

	function cycleTheme() {
		themeStore.toggle();
	}

	function setTheme(mode: ThemeMode) {
		themeStore.setMode(mode);
	}

	// Derived values for display
	let modeLabel = $derived(
		themeStore.mode === 'system' ? 'System' :
		themeStore.mode === 'light' ? 'Light' : 'Dark'
	);
</script>

<div class="relative group">
	<button
		class="flex items-center gap-1.5 px-2 py-1 rounded text-sm hover:bg-gray-700/50 transition-colors"
		onclick={cycleTheme}
		title="Theme: {modeLabel} (click to cycle)"
	>
		{#if themeStore.mode === 'system'}
			<!-- System/Auto icon -->
			<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"/>
			</svg>
		{:else if themeStore.mode === 'light'}
			<!-- Sun icon -->
			<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z"/>
			</svg>
		{:else}
			<!-- Moon icon -->
			<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z"/>
			</svg>
		{/if}
		<span class="text-xs text-theme-secondary hidden sm:inline">{modeLabel}</span>
	</button>

	<!-- Dropdown menu on hover -->
	<div class="absolute right-0 top-full mt-1 py-1 bg-theme-secondary border border-theme-primary rounded shadow-lg opacity-0 invisible group-hover:opacity-100 group-hover:visible transition-all z-50 min-w-[120px]">
		<button
			class="w-full px-3 py-1.5 text-left text-sm hover:bg-blue-600/20 flex items-center gap-2 {themeStore.mode === 'system' ? 'text-blue-400' : ''}"
			onclick={() => setTheme('system')}
		>
			<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"/>
			</svg>
			System
		</button>
		<button
			class="w-full px-3 py-1.5 text-left text-sm hover:bg-blue-600/20 flex items-center gap-2 {themeStore.mode === 'light' ? 'text-blue-400' : ''}"
			onclick={() => setTheme('light')}
		>
			<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z"/>
			</svg>
			Light
		</button>
		<button
			class="w-full px-3 py-1.5 text-left text-sm hover:bg-blue-600/20 flex items-center gap-2 {themeStore.mode === 'dark' ? 'text-blue-400' : ''}"
			onclick={() => setTheme('dark')}
		>
			<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z"/>
			</svg>
			Dark
		</button>
	</div>
</div>
