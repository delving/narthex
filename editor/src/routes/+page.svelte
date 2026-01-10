<script lang="ts">
	import { PaneGroup, Pane, PaneResizer } from 'paneforge';
	import SourceTree from '$lib/components/SourceTree.svelte';
	import FieldStats from '$lib/components/FieldStats.svelte';
	import CodeEditor from '$lib/components/CodeEditor.svelte';
	import CodeTweakPanel from '$lib/components/CodeTweakPanel.svelte';
	import Preview from '$lib/components/Preview.svelte';
	import { sampleSourceTree, sampleTargetTree } from '$lib/sampleData';
	import type { TreeNode } from '$lib/types';
	import { onMount } from 'svelte';

	// Import mappingsStore and code generator
	import { mappingsStore, type Mapping } from '$lib/stores/mappingStore';
	import { generateGroovyCode } from '$lib/utils/codeGenerator';

	// Prevent default drag/drop behavior on document to enable custom drag/drop
	onMount(() => {
		const preventDrop = (e: DragEvent) => {
			// Only prevent if it's not being handled by our tree nodes
			if (!(e.target as HTMLElement)?.closest('[data-node-id]')) {
				e.preventDefault();
			}
		};
		const preventDragOver = (e: DragEvent) => {
			// Only prevent if it's not being handled by our tree nodes
			if (!(e.target as HTMLElement)?.closest('[data-node-id]')) {
				e.preventDefault();
			}
		};

		// Handle custom tree-drop event (workaround for PaneForge blocking drop events)
		const handleTreeDrop = (e: CustomEvent) => {
			const { sourceNode, sourceTreeType, targetNodeId, targetTreeType } = e.detail;

			// Find the target node in the appropriate tree
			const targetTree = targetTreeType === 'target' ? sampleTargetTree : sampleSourceTree;
			const targetNode = findNode(targetTree, targetNodeId);

			if (!targetNode) {
				return;
			}

			// Create the mapping based on direction
			if (sourceTreeType === 'source' && targetTreeType === 'target') {
				mappingsStore.addMapping(sourceNode, targetNode);
			} else if (sourceTreeType === 'target' && targetTreeType === 'source') {
				mappingsStore.addMapping(targetNode, sourceNode);
			}
		};

		document.addEventListener('drop', preventDrop);
		document.addEventListener('dragover', preventDragOver);
		document.addEventListener('tree-drop', handleTreeDrop as EventListener);

		return () => {
			document.removeEventListener('drop', preventDrop);
			document.removeEventListener('dragover', preventDragOver);
			document.removeEventListener('tree-drop', handleTreeDrop as EventListener);
		};
	});

	// Placeholder for dataset info - will come from route params
	let datasetSpec = $state('amsterdam-museum');
	let mappingPrefix = $state('edm');

	// Track mappings from store
	let currentMappings = $state<Mapping[]>([]);

	// Track if user has manually edited the code (to avoid overwriting)
	let userHasEdited = $state(false);

	// Groovy code state - starts empty, will be populated by mappings
	let groovyCode = $state(generateGroovyCode([], datasetSpec, mappingPrefix));

	// Subscribe to mappings store
	onMount(() => {
		const unsubscribe = mappingsStore.subscribe((mappings) => {
			currentMappings = mappings;
		});

		return unsubscribe;
	});

	// Auto-generate code when mappings change (using $effect for proper reactivity)
	$effect(() => {
		const mappings = currentMappings;
		if (!userHasEdited || mappings.length === 0) {
			groovyCode = generateGroovyCode(mappings, datasetSpec, mappingPrefix);
		}
	});

	function handleCodeChange(newCode: string) {
		// Mark as user-edited if the change came from manual editing
		// We detect this by checking if the code differs from what would be generated
		const generatedCode = generateGroovyCode(currentMappings, datasetSpec, mappingPrefix);
		if (newCode !== generatedCode) {
			userHasEdited = true;
		}
		groovyCode = newCode;
	}

	// Regenerate code button handler
	function regenerateCode() {
		userHasEdited = false;
		groovyCode = generateGroovyCode(currentMappings, datasetSpec, mappingPrefix);
	}

	// Selection state
	let selectedSourceId = $state<string | null>(null);
	let selectedTargetId = $state<string | null>(null);
	let selectedNode = $state<TreeNode | null>(null);
	let selectedType = $state<'source' | 'target'>('source');

	// Code editor tab state
	let activeCodeTab = $state<'full' | 'tweak'>('full');

	// Shared record index for preview and tweak panel
	let currentRecordIndex = $state(0);

	// Selected mapping ID for tweak panel
	let selectedMappingId = $state<string | null>(null);

	function handleRecordChange(index: number) {
		currentRecordIndex = index;
	}

	function handleMappingSelect(id: string) {
		selectedMappingId = id;
	}

	// When a mapping badge is clicked in the tree, switch to tweak tab
	function handleMappingClick(mappingId: string) {
		selectedMappingId = mappingId;
		activeCodeTab = 'tweak';
	}

	// Helper to find node by id in tree
	function findNode(nodes: TreeNode[], id: string): TreeNode | null {
		for (const node of nodes) {
			if (node.id === id) return node;
			if (node.children) {
				const found = findNode(node.children, id);
				if (found) return found;
			}
		}
		return null;
	}

	function handleSourceSelect(node: TreeNode) {
		selectedSourceId = node.id;
		selectedTargetId = null;
		selectedNode = node;
		selectedType = 'source';
	}

	function handleTargetSelect(node: TreeNode) {
		selectedTargetId = node.id;
		selectedSourceId = null;
		selectedNode = node;
		selectedType = 'target';
	}

</script>

<!-- Header -->
<header class="flex items-center justify-between px-4 py-2 bg-gray-800 border-b border-gray-700">
	<div class="flex items-center gap-4">
		<h1 class="text-lg font-semibold">Mapping Editor</h1>
		<span class="text-gray-400">
			{datasetSpec} / {mappingPrefix}
		</span>
	</div>
	<div class="flex items-center gap-2">
		<button class="px-3 py-1.5 bg-gray-700 hover:bg-gray-600 rounded text-sm">
			Revert
		</button>
		<button class="px-3 py-1.5 bg-blue-600 hover:bg-blue-700 rounded text-sm font-medium">
			Save Mapping
		</button>
	</div>
</header>

<!-- Main Content -->
<main class="h-[calc(100vh-48px)]">
	<PaneGroup direction="vertical">
		<!-- Top section: Trees and connections -->
		<Pane defaultSize={60} minSize={30}>
			<PaneGroup direction="horizontal">
				<!-- Source Tree Panel -->
				<Pane defaultSize={35} minSize={20}>
					<div class="h-full flex flex-col bg-gray-900 border-r border-gray-700">
						<div class="px-3 py-2 bg-gray-800 border-b border-gray-700 flex items-center justify-between">
							<span class="text-sm font-medium">Source Structure</span>
							<span class="text-xs text-gray-500">XML</span>
						</div>
						<div class="flex-1 overflow-auto">
							<SourceTree
								nodes={sampleSourceTree}
								selectedId={selectedSourceId}
								treeType="source"
								onSelect={handleSourceSelect}
								onMappingClick={handleMappingClick}
							/>
						</div>
					</div>
				</Pane>

				<PaneResizer class="w-1 bg-gray-700 hover:bg-blue-500 transition-colors cursor-col-resize" />

				<!-- Field Stats Panel -->
				<Pane defaultSize={30} minSize={15}>
					<div class="h-full flex flex-col bg-gray-900">
						<div class="px-3 py-2 bg-gray-800 border-b border-gray-700">
							<span class="text-sm font-medium">Field Details</span>
						</div>
						<div class="flex-1 overflow-auto">
							<FieldStats node={selectedNode} type={selectedType} />
						</div>
					</div>
				</Pane>

				<PaneResizer class="w-1 bg-gray-700 hover:bg-blue-500 transition-colors cursor-col-resize" />

				<!-- Target Tree Panel -->
				<Pane defaultSize={35} minSize={20}>
					<div class="h-full flex flex-col bg-gray-900 border-l border-gray-700">
						<div class="px-3 py-2 bg-gray-800 border-b border-gray-700 flex items-center justify-between">
							<span class="text-sm font-medium">Target Schema</span>
							<span class="text-xs text-gray-500">EDM</span>
						</div>
						<div class="flex-1 overflow-auto">
							<SourceTree
								nodes={sampleTargetTree}
								selectedId={selectedTargetId}
								treeType="target"
								onSelect={handleTargetSelect}
								onMappingClick={handleMappingClick}
							/>
						</div>
					</div>
				</Pane>
			</PaneGroup>
		</Pane>

		<PaneResizer class="h-1 bg-gray-700 hover:bg-blue-500 transition-colors cursor-row-resize" />

		<!-- Bottom section: Code editor and preview -->
		<Pane defaultSize={40} minSize={20}>
			<PaneGroup direction="horizontal">
				<!-- Code Editor Panel -->
				<Pane defaultSize={50} minSize={30}>
					<div class="h-full flex flex-col bg-gray-900 border-r border-gray-700">
						<!-- Tab header -->
						<div class="px-3 py-2 bg-gray-800 border-b border-gray-700 flex items-center justify-between">
							<div class="flex items-center gap-1">
								<button
									class="px-3 py-1 text-sm rounded-t transition-colors"
									class:bg-gray-900={activeCodeTab === 'full'}
									class:text-white={activeCodeTab === 'full'}
									class:bg-transparent={activeCodeTab !== 'full'}
									class:text-gray-400={activeCodeTab !== 'full'}
									class:hover:text-gray-200={activeCodeTab !== 'full'}
									onclick={() => activeCodeTab = 'full'}
								>
									Full Code
								</button>
								<button
									class="px-3 py-1 text-sm rounded-t transition-colors"
									class:bg-gray-900={activeCodeTab === 'tweak'}
									class:text-white={activeCodeTab === 'tweak'}
									class:bg-transparent={activeCodeTab !== 'tweak'}
									class:text-gray-400={activeCodeTab !== 'tweak'}
									class:hover:text-gray-200={activeCodeTab !== 'tweak'}
									onclick={() => activeCodeTab = 'tweak'}
								>
									Tweak
								</button>
								{#if activeCodeTab === 'full'}
									{#if userHasEdited}
										<span class="text-xs text-yellow-500 ml-2">(edited)</span>
									{/if}
									{#if currentMappings.length > 0}
										<span class="text-xs text-gray-500 ml-1">({currentMappings.length} mappings)</span>
									{/if}
								{/if}
							</div>
							<div class="flex items-center gap-2">
								{#if activeCodeTab === 'full' && userHasEdited}
									<button
										class="text-xs text-yellow-400 hover:text-yellow-300"
										onclick={regenerateCode}
									>
										Regenerate
									</button>
								{/if}
								<button class="text-xs text-blue-400 hover:text-blue-300">
									Documentation
								</button>
							</div>
						</div>
						<!-- Tab content -->
						<div class="flex-1 overflow-hidden">
							{#if activeCodeTab === 'full'}
								<CodeEditor value={groovyCode} onChange={handleCodeChange} />
							{:else}
								<CodeTweakPanel
									currentRecordIndex={currentRecordIndex}
									onRecordChange={handleRecordChange}
									selectedMappingId={selectedMappingId}
									onMappingSelect={handleMappingSelect}
								/>
							{/if}
						</div>
					</div>
				</Pane>

				<PaneResizer class="w-1 bg-gray-700 hover:bg-blue-500 transition-colors cursor-col-resize" />

				<!-- Preview Panel -->
				<Pane defaultSize={50} minSize={30}>
					<Preview
						groovyCode={groovyCode}
						mappings={currentMappings}
						currentRecordIndex={currentRecordIndex}
						onRecordChange={handleRecordChange}
					/>
				</Pane>
			</PaneGroup>
		</Pane>
	</PaneGroup>
</main>
