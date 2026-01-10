<script lang="ts">
	import '../app.css';
	import favicon from '$lib/assets/favicon.svg';

	let { children } = $props();
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
	<title>Narthex Mapping Editor</title>
</svelte:head>

<div class="h-screen w-screen overflow-hidden">
	{@render children()}
</div>
