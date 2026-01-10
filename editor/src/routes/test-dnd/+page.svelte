<script lang="ts">
	let dropMessage = $state('Drop something here');
	let draggedItem = $state<string | null>(null);
	let mappings = $state<string[]>([]);

	function handleDragStart(e: DragEvent, item: string) {
		console.log('Drag start:', item);
		draggedItem = item;
		if (e.dataTransfer) {
			e.dataTransfer.effectAllowed = 'move';
			e.dataTransfer.setData('text/plain', item);
		}
	}

	function handleDragEnd() {
		console.log('Drag end');
		draggedItem = null;
	}

	function handleDragOver(e: DragEvent) {
		e.preventDefault();
		if (e.dataTransfer) {
			e.dataTransfer.dropEffect = 'move';
		}
	}

	function handleDrop(e: DragEvent, target: string) {
		e.preventDefault();
		console.log('Drop on', target, 'dragged item:', draggedItem);
		if (draggedItem) {
			mappings = [...mappings, `${draggedItem} → ${target}`];
			dropMessage = `Dropped ${draggedItem} on ${target}!`;
		}
		draggedItem = null;
	}

	const sourceItems = ['title', 'description', 'creator', 'date'];
	const targetItems = ['dc:title', 'dc:description', 'dc:creator', 'dc:date'];
</script>

<div class="p-8 bg-gray-900 min-h-screen text-white">
	<h1 class="text-2xl font-bold mb-8">Drag and Drop Test</h1>

	<div class="flex gap-8">
		<!-- Source items -->
		<div class="flex-1">
			<h2 class="text-lg font-semibold mb-4 text-green-400">Source (drag from here)</h2>
			<div class="space-y-2">
				{#each sourceItems as item}
					<div
						class="p-3 bg-gray-800 rounded cursor-grab border-2 border-transparent hover:border-green-500"
						class:opacity-50={draggedItem === item}
						draggable="true"
						on:dragstart={(e) => handleDragStart(e, item)}
						on:dragend={handleDragEnd}
					>
						{item}
					</div>
				{/each}
			</div>
		</div>

		<!-- Target items -->
		<div class="flex-1">
			<h2 class="text-lg font-semibold mb-4 text-blue-400">Target (drop here)</h2>
			<div class="space-y-2">
				{#each targetItems as item}
					<div
						class="p-3 bg-gray-800 rounded border-2 border-transparent hover:border-blue-500"
						class:border-blue-500={draggedItem !== null}
						on:dragover={handleDragOver}
						on:drop={(e) => handleDrop(e, item)}
					>
						{item}
					</div>
				{/each}
			</div>
		</div>
	</div>

	<!-- Status -->
	<div class="mt-8 p-4 bg-gray-800 rounded">
		<h3 class="font-semibold mb-2">Status:</h3>
		<p>{dropMessage}</p>
		<p class="text-sm text-gray-400 mt-2">Dragging: {draggedItem ?? 'nothing'}</p>
	</div>

	<!-- Mappings -->
	{#if mappings.length > 0}
		<div class="mt-4 p-4 bg-gray-800 rounded">
			<h3 class="font-semibold mb-2">Mappings created:</h3>
			<ul class="space-y-1">
				{#each mappings as mapping}
					<li class="text-green-400">{mapping}</li>
				{/each}
			</ul>
		</div>
	{/if}
</div>
