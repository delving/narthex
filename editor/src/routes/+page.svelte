<script lang="ts">
	import { PaneGroup, Pane, PaneResizer } from 'paneforge';
	import SourceTree from '$lib/components/SourceTree.svelte';
	import FieldStats from '$lib/components/FieldStats.svelte';
	import CodeEditor from '$lib/components/CodeEditor.svelte';
	import CodeTweakPanel from '$lib/components/CodeTweakPanel.svelte';
	import Preview from '$lib/components/Preview.svelte';
	import PreviewModal from '$lib/components/PreviewModal.svelte';
	import MappingModal from '$lib/components/MappingModal.svelte';
	import CodeEditModal from '$lib/components/CodeEditModal.svelte';
	import KeyboardShortcutsOverlay from '$lib/components/KeyboardShortcutsOverlay.svelte';
	import CommandPalette from '$lib/components/CommandPalette.svelte';
	import { sampleSourceTree, sampleTargetTree } from '$lib/sampleData';
	import type { TreeNode } from '$lib/types';
	import { onMount } from 'svelte';
	import { page } from '$app/stores';
	import { loadSourceTree, loadRecDef, loadMappings, generateFullGroovyCode, saveMapping, type LoadedMapping, type SaveMappingRequest } from '$lib/api/mappingEditor';
	import { editedCodeStore, hasUnsavedEdits, modifiedCount } from '$lib/stores/editedCodeStore';
	import DatasetPicker from '$lib/components/DatasetPicker.svelte';
	import StatisticsPanel from '$lib/components/StatisticsPanel.svelte';
	import ThemeToggle from '$lib/components/ThemeToggle.svelte';
	import { themeStore } from '$lib/stores/theme.svelte';

	// Editor mode: 'mapping' for normal mapping view, 'stats' for full statistics view
	let editorMode = $state<'mapping' | 'stats'>('mapping');

	// Import mappingsStore and code generator
	import { mappingsStore, type Mapping } from '$lib/stores/mappingStore';
	import { generateGroovyCode } from '$lib/utils/codeGenerator';
	import type { CustomFunction, CustomVariable } from '$lib/data/groovyReference';

	// Source tree state (from API or mock)
	let sourceNodes = $state<TreeNode[]>(sampleSourceTree);
	let isLoadingSource = $state(false);
	let sourceError = $state<string | null>(null);
	let useRealData = $state(false);

	// Target tree state (from API or mock)
	let targetNodes = $state<TreeNode[]>(sampleTargetTree);
	let isLoadingTarget = $state(false);
	let targetError = $state<string | null>(null);
	let useRealTargetData = $state(false);

	// Mappings loading state
	let isLoadingMappings = $state(false);
	let mappingsError = $state<string | null>(null);
	let loadedMappingsData = $state<{
		prefix: string;
		schemaVersion: string;
		facts: Record<string, string>;
		functions: Array<{ name: string; code: string }>;
	} | null>(null);
	let orphanPaths = $state<Set<string>>(new Set());
	let targetOrphanPaths = $state<Set<string>>(new Set());

	// Missing paths modal state
	let missingPathsModalOpen = $state(false);
	let missingPathsModalType = $state<'source' | 'target'>('source');

	// Save state
	let isSaving = $state(false);
	let saveError = $state<string | null>(null);
	let saveSuccess = $state<string | null>(null);

	// Constant input modal state
	let constantModalOpen = $state(false);
	let constantModalTarget = $state<TreeNode | null>(null);
	let constantValue = $state('');

	function openConstantModal(targetNode: TreeNode) {
		constantModalTarget = targetNode;
		constantValue = '';
		constantModalOpen = true;
	}

	function closeConstantModal() {
		constantModalOpen = false;
		constantModalTarget = null;
		constantValue = '';
	}

	function submitConstantMapping() {
		if (constantModalTarget && constantValue.trim()) {
			// Create a constant mapping with the entered value as label
			const constantSourceNode: TreeNode = {
				id: '_constant',
				name: '_constant',
				path: '/_constant',
				isConstant: true,
				hasValues: true
			};
			mappingsStore.addMapping(constantSourceNode, constantModalTarget, constantValue.trim());
		}
		closeConstantModal();
	}

	// Total record count for preview navigation
	let totalRecordCount = $state<number>(0);

	// Helper to extract total record count from source tree
	function extractTotalRecordCount(nodes: TreeNode[]): number {
		// Find the pocket node and get its quality.totalRecords
		for (const node of nodes) {
			if (node.name === 'pockets' && node.children) {
				for (const child of node.children) {
					if (child.name === 'pocket' && child.quality?.totalRecords) {
						return child.quality.totalRecords;
					}
				}
			}
			// Also check direct children for count
			if (node.quality?.totalRecords) {
				return node.quality.totalRecords;
			}
		}
		return 0;
	}

	// Load source tree from API when dataset spec is provided
	async function loadSourceData(spec: string) {
		isLoadingSource = true;
		sourceError = null;
		try {
			sourceNodes = await loadSourceTree(spec);
			useRealData = true;
			// Extract total record count for preview
			totalRecordCount = extractTotalRecordCount(sourceNodes);
			// After source tree is loaded, load existing mappings
			await loadExistingMappings(spec, sourceNodes);
		} catch (err) {
			sourceError = err instanceof Error ? err.message : 'Failed to load source tree';
			console.error('Failed to load source tree:', err);
			// Fall back to sample data on error
			sourceNodes = sampleSourceTree;
			useRealData = false;
			totalRecordCount = 0;
		} finally {
			isLoadingSource = false;
		}
	}

	// Load target tree (rec-def) from API
	async function loadTargetData(prefix: string) {
		isLoadingTarget = true;
		targetError = null;
		try {
			const recDef = await loadRecDef(prefix);
			targetNodes = recDef.tree;
			useRealTargetData = true;
		} catch (err) {
			targetError = err instanceof Error ? err.message : 'Failed to load rec-def';
			console.error('Failed to load rec-def:', err);
			// Fall back to sample data on error
			targetNodes = sampleTargetTree;
			useRealTargetData = false;
		} finally {
			isLoadingTarget = false;
		}
	}

	/**
	 * Build a lookup map from paths to tree nodes.
	 * Handles the path prefix issue:
	 * - Mapping paths use: /input/record/... or /input/... or /input/@id
	 * - Source tree paths use: /pockets/pocket/record/... or /pockets/pocket/@id
	 *
	 * We need to map between these conventions:
	 * - /pockets/pocket/record/... -> /input/record/...
	 * - /pockets/pocket/@id -> /input/@id
	 * - /pockets/pocket -> /input
	 */
	function buildPathToNodeMap(nodes: TreeNode[], map: Map<string, TreeNode> = new Map()): Map<string, TreeNode> {
		for (const node of nodes) {
			if (node.path) {
				// Store by full path
				map.set(node.path, node);

				// Convert /pockets/pocket/... to /input/... for mapping compatibility
				// This handles all variations:
				// - /pockets/pocket -> /input
				// - /pockets/pocket/@id -> /input/@id
				// - /pockets/pocket/record/... -> /input/record/...
				const inputPath = node.path.replace(/^\/pockets\/pocket/, '/input');
				if (inputPath !== node.path) {
					map.set(inputPath, node);
				}

				// Also store by path without any prefix for edge cases
				const shortPath = node.path.replace(/^\/pockets\/pocket/, '');
				if (shortPath !== node.path && shortPath !== inputPath) {
					map.set(shortPath, node);
				}
			}
			if (node.children) {
				buildPathToNodeMap(node.children, map);
			}
		}
		return map;
	}

	/**
	 * Normalize a mapping path to match rec-def format.
	 * Mapping files use a custom prefix on the root element (e.g., /edm:RDF, /abc:Root),
	 * but rec-def uses unprefixed root (e.g., /RDF, /Root).
	 * Only strips the prefix from the FIRST element (root).
	 * - /edm:RDF/edm:ProvidedCHO/dc:title -> /RDF/edm:ProvidedCHO/dc:title
	 * - /abc:Root/foo/bar -> /Root/foo/bar
	 */
	function normalizeMappingPath(path: string): string {
		// Strip namespace prefix from root element only: /prefix:Element -> /Element
		// The regex matches: leading slash, then prefix with colon, at the start only
		return path.replace(/^\/[a-zA-Z0-9_-]+:/, '/');
	}

	/**
	 * Check if a path is just the root element (e.g., /edm:RDF, /RDF).
	 * Root-only paths have no children and shouldn't be shown as "missing".
	 */
	function isRootElementPath(path: string): boolean {
		// Matches /prefix:Element or /Element with no further segments
		return /^\/[a-zA-Z0-9_-]+(:[a-zA-Z0-9_-]+)?$/.test(path);
	}

	/**
	 * Strip qualifier from a path segment for matching.
	 * Mapping files use qualifiers like edm:Agent[person], edm:Agent[creator]
	 * to create multiple instances of the same element type.
	 * - /edm:RDF/edm:Agent[person]/skos:prefLabel -> /edm:RDF/edm:Agent/skos:prefLabel
	 */
	function stripQualifiers(path: string): string {
		return path.replace(/\[[^\]]+\]/g, '');
	}

	/**
	 * Extract qualifier from a path if present.
	 * Returns { qualifier, qualifiedSegment, segmentIndex } or null if no qualifier.
	 * - /edm:RDF/edm:Agent[person]/skos:prefLabel -> { qualifier: "person", qualifiedSegment: "edm:Agent[person]", segmentIndex: 2 }
	 */
	function extractQualifier(path: string): { qualifier: string; qualifiedSegment: string; segmentIndex: number } | null {
		const match = path.match(/([^\/]+)\[([^\]]+)\]/);
		if (!match) return null;
		const qualifiedSegment = match[0];
		const qualifier = match[2];
		const segments = path.split('/');
		const segmentIndex = segments.findIndex(s => s.includes(`[${qualifier}]`));
		return { qualifier, qualifiedSegment, segmentIndex };
	}

	/**
	 * Get the qualified element path (the path up to and including the qualified element).
	 * - /edm:RDF/edm:Agent[person]/skos:prefLabel -> /edm:RDF/edm:Agent[person]
	 */
	function getQualifiedElementPath(path: string): string | null {
		const match = path.match(/^(.*?\[[^\]]+\])/);
		return match ? match[1] : null;
	}

	/**
	 * Get the base element path (qualified element path with qualifier stripped).
	 * - /edm:RDF/edm:Agent[person] -> /edm:RDF/edm:Agent
	 */
	function getBaseElementPath(qualifiedPath: string): string {
		return stripQualifiers(qualifiedPath);
	}

	/**
	 * Build a lookup map from paths to target tree nodes.
	 * Simply indexes by the rec-def path as-is.
	 */
	function buildTargetPathToNodeMap(nodes: TreeNode[], map: Map<string, TreeNode> = new Map()): Map<string, TreeNode> {
		for (const node of nodes) {
			if (node.path) {
				map.set(node.path, node);
			}
			if (node.children) {
				buildTargetPathToNodeMap(node.children, map);
			}
		}
		return map;
	}

	/**
	 * Index qualified paths in the map.
	 * For qualified variant nodes, also index by their qualified path.
	 */
	function indexQualifiedPaths(nodes: TreeNode[], map: Map<string, TreeNode>): void {
		for (const node of nodes) {
			if (node.isQualifiedVariant && node.path) {
				// The node.path is in rec-def format (e.g., /RDF/edm:Agent[person])
				map.set(node.path, node);
				// Note: We don't need to index with mapping prefix format because
				// findTargetNode normalizes mapping paths before lookup
			}
			if (node.children) {
				indexQualifiedPaths(node.children, map);
			}
		}
	}

	/**
	 * Insert qualified variant nodes into the target tree based on mapping paths.
	 * For each qualified path (e.g., /edm:RDF/edm:Agent[person]), creates a variant
	 * of the base element (edm:Agent) with the qualifier label.
	 */
	function insertQualifiedVariants(
		tree: TreeNode[],
		qualifiedPaths: Set<string>,
		pathMap: Map<string, TreeNode>
	): TreeNode[] {
		// Deep clone the tree
		const newTree = JSON.parse(JSON.stringify(tree)) as TreeNode[];

		// Group qualified paths by their base element path
		const variantsByBase = new Map<string, Set<string>>();
		for (const qualifiedPath of qualifiedPaths) {
			const basePath = getBaseElementPath(qualifiedPath);
			const normalizedBase = normalizeMappingPath(basePath);
			if (!variantsByBase.has(normalizedBase)) {
				variantsByBase.set(normalizedBase, new Set());
			}
			variantsByBase.get(normalizedBase)!.add(qualifiedPath);
		}

		// For each base element, find it in the tree and add qualified variants
		for (const [basePath, variants] of variantsByBase) {
			const baseNode = findNodeByPath(newTree, basePath);
			if (!baseNode) {
				console.warn(`Base node not found for qualified variants: ${basePath}`);
				continue;
			}

			// Find the parent of the base node to insert siblings
			const parentPath = basePath.substring(0, basePath.lastIndexOf('/'));
			const parentNode = parentPath ? findNodeByPath(newTree, parentPath) : null;
			const siblings = parentNode?.children || newTree;

			// Find the index of the base node
			const baseIndex = siblings.findIndex(n => n.path === basePath);
			if (baseIndex === -1) continue;

			// Create qualified variants and insert them after the base node
			const variantNodes: TreeNode[] = [];
			for (const qualifiedPath of variants) {
				const qualifierInfo = extractQualifier(qualifiedPath);
				if (!qualifierInfo) continue;

				// Create the qualified variant node
				const variantNode: TreeNode = {
					...JSON.parse(JSON.stringify(baseNode)),
					id: `${baseNode.id}_${qualifierInfo.qualifier}`,
					path: normalizeMappingPath(qualifiedPath), // Use normalized path (e.g., /RDF/edm:Agent[person])
					qualifier: qualifierInfo.qualifier,
					isQualifiedVariant: true,
					basePath: basePath,
					// Update children paths to include qualifier
					children: baseNode.children ? updateChildrenPaths(
						JSON.parse(JSON.stringify(baseNode.children)),
						basePath,
						normalizeMappingPath(qualifiedPath),
						qualifierInfo.qualifier
					) : undefined
				};

				variantNodes.push(variantNode);
			}

			// Insert variants after the base node
			siblings.splice(baseIndex + 1, 0, ...variantNodes);
		}

		return newTree;
	}

	/**
	 * Update children paths to reflect qualified parent.
	 */
	function updateChildrenPaths(
		children: TreeNode[],
		oldBasePath: string,
		newBasePath: string,
		qualifier: string
	): TreeNode[] {
		return children.map(child => ({
			...child,
			id: `${child.id}_${qualifier}`,
			path: child.path.replace(oldBasePath, newBasePath),
			children: child.children
				? updateChildrenPaths(child.children, oldBasePath, newBasePath, qualifier)
				: undefined
		}));
	}

	/**
	 * Find a node by path in the tree.
	 */
	function findNodeByPath(nodes: TreeNode[], path: string): TreeNode | null {
		for (const node of nodes) {
			if (node.path === path) return node;
			if (node.children) {
				const found = findNodeByPath(node.children, path);
				if (found) return found;
			}
		}
		return null;
	}

	/**
	 * Look up a target node by mapping path.
	 * Tries multiple normalizations to match rec-def paths:
	 * 1. Exact match
	 * 2. Normalized (strip root prefix): /edm:RDF/... -> /RDF/...
	 * 3. With qualifiers stripped: edm:Agent[person] -> edm:Agent
	 * 4. Both normalized and qualifier-stripped
	 */
	function findTargetNode(targetPathMap: Map<string, TreeNode>, mappingPath: string): TreeNode | undefined {
		// Try exact match first
		let node = targetPathMap.get(mappingPath);
		if (node) return node;

		// Try normalized (strip root prefix)
		const normalizedPath = normalizeMappingPath(mappingPath);
		node = targetPathMap.get(normalizedPath);
		if (node) return node;

		// Try with qualifiers stripped
		const strippedPath = stripQualifiers(mappingPath);
		if (strippedPath !== mappingPath) {
			node = targetPathMap.get(strippedPath);
			if (node) return node;
		}

		// Try both normalized and qualifier-stripped
		const normalizedStripped = stripQualifiers(normalizedPath);
		if (normalizedStripped !== normalizedPath) {
			node = targetPathMap.get(normalizedStripped);
		}
		return node;
	}

	/**
	 * Find or create a node at the given path in the tree.
	 * Creates intermediate nodes as needed, all marked as orphan.
	 * Returns the modified tree.
	 *
	 * Path conventions:
	 * - /input/... -> under pocket (e.g., /input/record/... -> /pockets/pocket/record/...)
	 * - /facts/... -> sibling of pocket (e.g., /facts/provider -> /facts/provider)
	 * - /constant -> sibling of pocket
	 */
	function insertOrphanPath(tree: TreeNode[], orphanPath: string, originalMappingPath: string): TreeNode[] {
		// Deep clone the tree to avoid mutation issues
		const newTree = JSON.parse(JSON.stringify(tree)) as TreeNode[];

		// Find the pockets node first
		const pocketsNode = newTree.find(n => n.name === 'pockets');
		if (!pocketsNode || !pocketsNode.children) {
			console.warn('Cannot find pockets node in tree');
			return tree;
		}

		let currentLevel: TreeNode[];
		let currentTreePath: string;
		let segments: string[];

		// Determine where to start based on path prefix
		if (orphanPath.startsWith('/input')) {
			// /input/... paths go under pocket
			const pocketNode = pocketsNode.children.find(n => n.name === 'pocket');
			if (!pocketNode) {
				console.warn('Cannot find pocket node in tree');
				return tree;
			}
			if (!pocketNode.children) {
				pocketNode.children = [];
			}
			currentLevel = pocketNode.children;
			currentTreePath = '/pockets/pocket';
			// Remove /input prefix to get the remaining path
			segments = orphanPath.replace(/^\/input/, '').split('/').filter(s => s);
		} else if (orphanPath.startsWith('/facts') || orphanPath.startsWith('/constant')) {
			// /facts/... and /constant paths are siblings of pocket (under pockets)
			currentLevel = pocketsNode.children;
			currentTreePath = '';
			segments = orphanPath.split('/').filter(s => s);
		} else {
			// Unknown path format - try under pocket
			const pocketNode = pocketsNode.children.find(n => n.name === 'pocket');
			if (!pocketNode) {
				console.warn('Cannot find pocket node in tree');
				return tree;
			}
			if (!pocketNode.children) {
				pocketNode.children = [];
			}
			currentLevel = pocketNode.children;
			currentTreePath = '/pockets/pocket';
			segments = orphanPath.split('/').filter(s => s);
		}

		if (segments.length === 0) return tree;

		for (let i = 0; i < segments.length; i++) {
			const segment = segments[i];
			currentTreePath += '/' + segment;
			const isLast = i === segments.length - 1;

			// Look for existing node at this level by name or path
			let existingNode = currentLevel.find(n =>
				n.name === segment ||
				n.path === currentTreePath ||
				n.path.endsWith('/' + segment)
			);

			if (existingNode) {
				// Node exists - if it's the last segment, mark as orphan
				if (isLast) {
					existingNode.isOrphan = true;
					// Store the original mapping path for debugging
					(existingNode as TreeNode & { mappingPath?: string }).mappingPath = originalMappingPath;
				} else if (existingNode.children) {
					currentLevel = existingNode.children;
				} else {
					// Need to add children to this node
					existingNode.children = [];
					currentLevel = existingNode.children;
				}
			} else {
				// Node doesn't exist - create orphan node
				const newNode: TreeNode & { mappingPath?: string } = {
					id: `orphan_${currentTreePath.replace(/\//g, '_').replace(/^_/, '')}`,
					name: segment,
					path: currentTreePath,
					isOrphan: true,
					hasValues: isLast,
					count: 0,
					children: isLast ? undefined : [],
					mappingPath: originalMappingPath
				};

				// Insert orphans after special nodes (constant, facts) but before other children
				const insertIdx = currentLevel.findIndex(n => !n.isConstant && !n.isFacts && !n.isFact);
				if (insertIdx >= 0) {
					currentLevel.splice(insertIdx, 0, newNode);
				} else {
					currentLevel.push(newNode);
				}

				if (!isLast && newNode.children) {
					currentLevel = newNode.children;
				}
			}
		}

		return newTree;
	}

	/**
	 * Populate the facts node in the tree with fact entries from the mapping
	 * Uses /facts/... paths to match mapping file convention (e.g., /facts/provider)
	 */
	function populateFactsInTree(tree: TreeNode[], facts: Record<string, string>): TreeNode[] {
		// Deep clone to avoid mutation
		const newTree = JSON.parse(JSON.stringify(tree)) as TreeNode[];

		// Find the facts node (should be under pockets)
		function findAndPopulateFacts(nodes: TreeNode[]): boolean {
			for (const node of nodes) {
				if (node.id === 'facts') {
					// Create fact children with paths matching mapping convention
					node.children = Object.entries(facts).map(([key, value]): TreeNode => ({
						id: `fact_${key}`,
						name: key,
						path: `/facts/${key}`,
						isAttribute: false,
						hasValues: true,
						isFact: true,
						count: 0,
						factValue: value
					}));
					node.count = node.children.length;
					return true;
				}
				if (node.children && findAndPopulateFacts(node.children)) {
					return true;
				}
			}
			return false;
		}

		findAndPopulateFacts(newTree);
		return newTree;
	}

	/**
	 * Load existing mappings from server and populate the store
	 * Detects orphaned mappings (source paths not in source tree)
	 */
	async function loadExistingMappings(spec: string, sourceTree: TreeNode[]) {
		isLoadingMappings = true;
		mappingsError = null;
		try {
			const result = await loadMappings(spec);
			if (!result) {
				console.log('No existing mappings found for dataset:', spec);
				return;
			}

			loadedMappingsData = {
				prefix: result.prefix,
				schemaVersion: result.schemaVersion,
				facts: result.facts,
				functions: result.functions || []
			};

			// If the mapping's prefix differs from current, reload the target rec-def
			if (result.prefix && result.prefix !== mappingPrefix) {
				console.log(`Mapping prefix "${result.prefix}" differs from current "${mappingPrefix}", reloading rec-def`);
				mappingPrefix = result.prefix;
				await loadTargetData(mappingPrefix);
			}

			// Start with the current tree
			let modifiedTree = sourceTree;

			// FIRST: Populate facts into the facts node
			// This must happen BEFORE building the path map so /facts/provider etc. are indexed
			if (result.facts && Object.keys(result.facts).length > 0) {
				modifiedTree = populateFactsInTree(modifiedTree, result.facts);
				console.log(`Populated ${Object.keys(result.facts).length} facts into tree`);
			}

			// THEN: Build lookup maps (now includes constant, facts, and fact children)
			const sourcePathMap = buildPathToNodeMap(modifiedTree);
			const targetPathMap = buildTargetPathToNodeMap(targetNodes);

			// Debug: print sample target paths (rec-def format)
			console.log('Target schema paths (sample):', Array.from(targetPathMap.keys()).slice(0, 20));
			console.log('Total target paths:', targetPathMap.size);

			// STEP 1: Collect all qualified element paths from mappings
			const qualifiedPaths = new Set<string>();
			for (const m of result.mappings) {
				const qualifiedElementPath = getQualifiedElementPath(m.targetPath);
				if (qualifiedElementPath) {
					qualifiedPaths.add(qualifiedElementPath);
				}
			}

			// STEP 2: Insert qualified variants into target tree
			let modifiedTargetTree = targetNodes;
			if (qualifiedPaths.size > 0) {
				modifiedTargetTree = insertQualifiedVariants(targetNodes, qualifiedPaths, targetPathMap);
				// Re-index target paths with new qualified variants
				targetPathMap.clear();
				buildTargetPathToNodeMap(modifiedTargetTree, targetPathMap);
				// Also index qualified paths directly (with their qualifier)
				indexQualifiedPaths(modifiedTargetTree, targetPathMap);
				console.log(`Inserted ${qualifiedPaths.size} qualified variants into target tree`);
			}

			// Clear existing mappings before loading new ones
			mappingsStore.clear();

			// Track orphan paths
			const newOrphanPaths = new Set<string>();

			// Track target paths not found in rec-def
			const targetNotFound: string[] = [];

			// Convert loaded mappings to store format and detect orphans
			const storeCompatibleMappings: Mapping[] = result.mappings.map((m: LoadedMapping) => {
				// Find source node - try exact match first, then without prefix
				const sourceNode = sourcePathMap.get(m.sourcePath);
				// Find target node - handles both /edm:RDF and /RDF formats, plus qualifiers
				const targetNode = findTargetNode(targetPathMap, m.targetPath);

				const isOrphan = !sourceNode;
				if (isOrphan) {
					newOrphanPaths.add(m.sourcePath);
				}

				// Track target paths not found in rec-def (exclude root element like /edm:RDF)
				if (!targetNode && !isRootElementPath(m.targetPath)) {
					targetNotFound.push(m.targetPath);
				}

				// Use actual node IDs if found, otherwise fall back to generated IDs
				const sourceId = sourceNode?.id || m.sourceId;
				const sourceName = sourceNode?.name || m.sourceName;
				const targetId = targetNode?.id || m.targetId;
				const targetName = targetNode?.name || m.targetName;

				return {
					id: m.id,
					sourcePath: m.sourcePath,
					sourceId,
					sourceName,
					targetPath: m.targetPath,
					targetId,
					targetName,
					groovyCode: m.groovyCode,
					operator: m.operator,
					documentation: m.documentation,
					siblings: m.siblings,
					isOrphan
				};
			});

			// Update orphan paths state (source and target)
			orphanPaths = newOrphanPaths;
			targetOrphanPaths = new Set(targetNotFound);

			// If there are orphan paths, insert them into the tree at their correct positions
			if (newOrphanPaths.size > 0) {
				for (const orphanPath of newOrphanPaths) {
					modifiedTree = insertOrphanPath(modifiedTree, orphanPath, orphanPath);
				}
				console.log(`Inserted ${newOrphanPaths.size} orphan paths into source tree`);

				// Update mappings with correct IDs for the newly created orphan nodes
				// The ID should match the tree path, not the mapping path
				for (const mapping of storeCompatibleMappings) {
					if (mapping.isOrphan) {
						// Convert /input/... to /pockets/pocket/... for ID generation
						const treePath = mapping.sourcePath.replace(/^\/input/, '/pockets/pocket');
						mapping.sourceId = `orphan_${treePath.replace(/\//g, '_').replace(/^_/, '')}`;
					}
				}
			}

			// Update source nodes with all modifications
			sourceNodes = modifiedTree;

			// Update target nodes with qualified variants
			if (modifiedTargetTree !== targetNodes) {
				targetNodes = modifiedTargetTree;
			}

			// Set all mappings at once
			mappingsStore.set(storeCompatibleMappings);

			console.log(`Loaded ${storeCompatibleMappings.length} mappings, ${newOrphanPaths.size} source orphans, ${targetNotFound.length} target orphans`);
		} catch (err) {
			mappingsError = err instanceof Error ? err.message : 'Failed to load mappings';
			console.error('Failed to load mappings:', err);
		} finally {
			isLoadingMappings = false;
		}
	}

	// Sample custom functions (would come from mapping/rec-def in real app)
	const sampleCustomFunctions: CustomFunction[] = [
		{
			name: 'cleanTitle()',
			signature: 'cleanTitle(text)',
			description: 'Clean and normalize title text, removing extra whitespace and special characters',
			example: 'cleanTitle(input.record.title.text())',
			insertText: 'cleanTitle()',
			category: 'Custom Functions'
		},
		{
			name: 'formatDate()',
			signature: 'formatDate(dateStr, inputFormat, outputFormat)',
			description: 'Parse and reformat date strings between different formats',
			example: 'formatDate("1920-05-15", "yyyy-MM-dd", "dd/MM/yyyy")',
			insertText: 'formatDate(, "", "")',
			category: 'Custom Functions'
		},
		{
			name: 'lookupVocab()',
			signature: 'lookupVocab(term, vocabularyName)',
			description: 'Look up a term in a controlled vocabulary and return the canonical URI',
			example: 'lookupVocab("painting", "aat")',
			insertText: 'lookupVocab(, "")',
			category: 'Custom Functions'
		}
	];

	// Sample custom variables (would come from rec-def in real app)
	const sampleCustomVariables: CustomVariable[] = [
		{
			name: 'provider',
			type: 'String',
			description: 'Data provider name from mapping facts',
			insertText: 'provider'
		},
		{
			name: 'baseUrl',
			type: 'String',
			description: 'Base URL for generating URIs',
			insertText: 'baseUrl'
		}
	];

	// Keyboard shortcuts overlay state
	let shortcutsOverlayOpen = $state(false);

	function toggleShortcutsOverlay() {
		shortcutsOverlayOpen = !shortcutsOverlayOpen;
	}

	// Command palette state
	let commandPaletteOpen = $state(false);

	function toggleCommandPalette() {
		commandPaletteOpen = !commandPaletteOpen;
	}

	// Define available commands
	const commands = $derived([
		// Navigation
		{
			id: 'toggle-mode',
			label: 'Toggle Mapping/Statistics Mode',
			description: 'Switch between mapping editor and statistics explorer',
			shortcut: ['Ctrl', '5'],
			category: 'Navigation',
			action: () => { editorMode = editorMode === 'mapping' ? 'stats' : 'mapping'; }
		},
		{
			id: 'goto-mapping-mode',
			label: 'Switch to Mapping Mode',
			description: 'Show source tree, target schema, and mapping tools',
			category: 'Navigation',
			action: () => { editorMode = 'mapping'; }
		},
		{
			id: 'goto-stats-mode',
			label: 'Switch to Statistics Mode',
			description: 'Show full statistics panel for source field analysis',
			category: 'Navigation',
			action: () => { editorMode = 'stats'; }
		},
		{
			id: 'goto-full-code',
			label: 'Go to Full Code Editor',
			description: 'Switch to the full Groovy code view',
			category: 'Navigation',
			action: () => { activeCodeTab = 'full'; }
		},
		{
			id: 'goto-tweak',
			label: 'Go to Tweak Panel',
			description: 'Switch to the per-mapping tweak view',
			category: 'Navigation',
			action: () => { activeCodeTab = 'tweak'; }
		},
		// Mappings
		{
			id: 'clear-mappings',
			label: 'Clear All Mappings',
			description: 'Remove all current mappings',
			category: 'Mappings',
			action: () => { mappingsStore.clear(); }
		},
		{
			id: 'regenerate-code',
			label: 'Regenerate Groovy Code',
			description: 'Regenerate code from current mappings',
			shortcut: ['Ctrl', 'Shift', 'G'],
			category: 'Code',
			action: () => { regenerateCode(); }
		},
		// Records
		{
			id: 'prev-record',
			label: 'Previous Record',
			description: 'Go to the previous sample record',
			shortcut: ['['],
			category: 'Records',
			action: () => { if (currentRecordIndex > 0) currentRecordIndex--; }
		},
		{
			id: 'next-record',
			label: 'Next Record',
			description: 'Go to the next sample record',
			shortcut: [']'],
			category: 'Records',
			action: () => { currentRecordIndex++; }
		},
		// Help
		{
			id: 'show-shortcuts',
			label: 'Show Keyboard Shortcuts',
			description: 'Display all available keyboard shortcuts',
			shortcut: ['?'],
			category: 'Help',
			action: () => { shortcutsOverlayOpen = true; }
		},
		// Actions
		{
			id: 'save-mapping',
			label: 'Save Mapping',
			description: 'Save the current mapping to the server',
			shortcut: ['Ctrl', 'S'],
			category: 'Actions',
			action: () => { alert('Save not implemented yet'); }
		},
		{
			id: 'revert-mapping',
			label: 'Revert Changes',
			description: 'Revert all changes to the last saved state',
			category: 'Actions',
			action: () => { alert('Revert not implemented yet'); }
		}
	]);

	// Global keyboard shortcut handler (used with svelte:window)
	function handleGlobalKeydown(e: KeyboardEvent) {
		// Track Ctrl key state
		if (e.key === 'Control' || e.ctrlKey || e.metaKey) {
			ctrlHeld = true;
		}

		// Don't trigger if user is typing in an input, textarea, or contenteditable
		const target = e.target as HTMLElement;
		const isEditing =
			target.tagName === 'INPUT' ||
			target.tagName === 'TEXTAREA' ||
			target.isContentEditable ||
			target.closest('.monaco-editor');

		// Ctrl+K / Cmd+K - Open command palette (works everywhere)
		// Use e.code for reliability across keyboard layouts
		if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K' || e.code === 'KeyK')) {
			e.preventDefault();
			e.stopPropagation();
			toggleCommandPalette();
			return;
		}

		// Ctrl+P - Alternative command palette trigger (like VS Code)
		if ((e.ctrlKey || e.metaKey) && e.shiftKey && (e.key === 'p' || e.key === 'P' || e.code === 'KeyP')) {
			e.preventDefault();
			e.stopPropagation();
			toggleCommandPalette();
			return;
		}

		// ? - Show keyboard shortcuts (works even in some contexts)
		if (e.key === '?' && !isEditing) {
			e.preventDefault();
			toggleShortcutsOverlay();
			return;
		}

		// Escape - close overlays
		if (e.key === 'Escape') {
			if (commandPaletteOpen) {
				e.preventDefault();
				commandPaletteOpen = false;
				return;
			}
			if (shortcutsOverlayOpen) {
				e.preventDefault();
				shortcutsOverlayOpen = false;
				return;
			}
		}

		// Record navigation shortcuts (when not editing)
		if (!isEditing) {
			if (e.key === '[') {
				e.preventDefault();
				if (currentRecordIndex > 0) currentRecordIndex--;
				return;
			}
			if (e.key === ']') {
				e.preventDefault();
				currentRecordIndex++;
				return;
			}
		}

		// Ctrl+1-5 - Jump to panes / Toggle mode
		if ((e.ctrlKey || e.metaKey) && !e.shiftKey && !e.altKey) {
			switch (e.key) {
				case '1':
					e.preventDefault();
					focusPane(sourcePaneRef, 'Source Structure');
					return;
				case '2':
					e.preventDefault();
					if (editorMode === 'mapping') {
						focusPane(targetPaneRef, 'Target Schema');
					}
					return;
				case '3':
					e.preventDefault();
					focusPane(codePaneRef, 'Code Editor');
					return;
				case '4':
					e.preventDefault();
					focusPane(previewPaneRef, 'Preview');
					return;
				case '5':
					e.preventDefault();
					editorMode = editorMode === 'mapping' ? 'stats' : 'mapping';
					return;
			}
		}

		// Ctrl+' - Toggle between code editor tabs
		if ((e.ctrlKey || e.metaKey) && e.key === "'") {
			e.preventDefault();
			activeCodeTab = activeCodeTab === 'full' ? 'tweak' : 'full';
			return;
		}
	}

	// Handle key up to reset Ctrl state
	function handleGlobalKeyup(e: KeyboardEvent) {
		if (e.key === 'Control' || e.key === 'Meta') {
			ctrlHeld = false;
		}
	}

	// Reset Ctrl state when window loses focus
	function handleWindowBlur() {
		ctrlHeld = false;
	}

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
			const targetTree = targetTreeType === 'target' ? targetNodes : sourceNodes;
			const targetNode = findNode(targetTree, targetNodeId);

			if (!targetNode) {
				return;
			}

			// Handle constant node - show modal to enter value
			if (sourceNode.isConstant && targetTreeType === 'target') {
				openConstantModal(targetNode);
				return;
			}

			// Create the mapping based on direction
			if (sourceTreeType === 'source' && targetTreeType === 'target') {
				mappingsStore.addMapping(sourceNode, targetNode);
			} else if (sourceTreeType === 'target' && targetTreeType === 'source') {
				mappingsStore.addMapping(targetNode, sourceNode);
			}
		};

		// Add keyboard listener with capture to intercept before browser defaults
		const handleKeydownCapture = (e: KeyboardEvent) => {
			// Ctrl+K / Cmd+K - must capture before browser uses it
			if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K' || e.code === 'KeyK')) {
				e.preventDefault();
				e.stopPropagation();
				toggleCommandPalette();
			}
		};

		document.addEventListener('keydown', handleKeydownCapture, { capture: true });
		document.addEventListener('drop', preventDrop);
		document.addEventListener('dragover', preventDragOver);
		document.addEventListener('tree-drop', handleTreeDrop as EventListener);

		return () => {
			document.removeEventListener('keydown', handleKeydownCapture, { capture: true });
			document.removeEventListener('drop', preventDrop);
			document.removeEventListener('dragover', preventDragOver);
			document.removeEventListener('tree-drop', handleTreeDrop as EventListener);
		};
	});

	// Dataset info - from URL params (?spec=...) or defaults
	let datasetSpec = $state<string | null>(null);
	let mappingPrefix = $state('edm');

	// Handle dataset selection from picker
	function handleDatasetSelect(spec: string) {
		// Update URL without page reload
		const url = new URL(window.location.href);

		if (spec) {
			// Real dataset selected
			url.searchParams.set('spec', spec);
			window.history.pushState({}, '', url.toString());
			datasetSpec = spec;
			loadSourceData(spec);
		} else {
			// Sample data selected - clear URL param and reset to sample
			url.searchParams.delete('spec');
			window.history.pushState({}, '', url.toString());
			datasetSpec = null;
			sourceNodes = sampleSourceTree;
			useRealData = false;
			sourceError = null;
		}
	}

	// Handle save mapping
	async function handleSave() {
		if (!datasetSpec) {
			saveError = 'No dataset selected. Please select a dataset first.';
			return;
		}

		isSaving = true;
		saveError = null;
		saveSuccess = null;

		try {
			// Get current mappings from store
			const mappings = currentMappings;

			// Get all edited code from the editedCodeStore
			const editedCodes = editedCodeStore.getAllEdited();
			const editedCodeMap = new Map(editedCodes.map(e => [e.outputPath, e.groovyCode]));

			console.log('Edited codes to merge:', editedCodes.length, editedCodes);

			// Convert to API format, merging in edited code
			const saveData: SaveMappingRequest = {
				prefix: loadedMappingsData?.prefix || mappingPrefix,
				schemaVersion: loadedMappingsData?.schemaVersion || '1.0',
				facts: loadedMappingsData?.facts || {},
				functions: loadedMappingsData?.functions || [],
				mappings: mappings.map(m => {
					// Check if this mapping has edited code
					const editedCode = editedCodeMap.get(m.targetPath);
					return {
						inputPath: m.sourcePath,
						outputPath: m.targetPath,
						// Use edited code if available, otherwise use original
						groovyCode: editedCode !== undefined ? editedCode : m.groovyCode,
						operator: m.operator,
						documentation: m.documentation,
						siblings: m.siblings
					};
				}),
				description: `Saved from mapping editor`
			};

			console.log('Saving mapping with', saveData.mappings.length, 'mappings');
			// Log mappings with groovyCode to verify edits are included
			const mappingsWithCode = saveData.mappings.filter(m => m.groovyCode);
			console.log('Mappings with groovyCode:', mappingsWithCode.length, mappingsWithCode.map(m => ({ path: m.outputPath, code: m.groovyCode?.substring(0, 50) })));

			const saveTime = Date.now();
			const result = await saveMapping(datasetSpec, saveData);

			if (result.success) {
				// Check if this is a new version or existing (no changes)
				const versionTime = result.version?.timestamp ? new Date(result.version.timestamp).getTime() : 0;
				const isNewVersion = versionTime > saveTime - 5000; // Version created within last 5 seconds

				if (isNewVersion) {
					saveSuccess = `Mapping saved successfully! Version: ${result.version?.hash?.substring(0, 8) || 'new'}`;
					// Clear edited code store after successful save
					editedCodeStore.clearAll();
				} else {
					saveSuccess = `No changes detected. Current version: ${result.version?.hash?.substring(0, 8) || 'unknown'}`;
				}
				// Clear success message after 5 seconds
				setTimeout(() => {
					saveSuccess = null;
				}, 5000);
			} else {
				saveError = result.error || 'Failed to save mapping';
			}
		} catch (err) {
			saveError = err instanceof Error ? err.message : 'Failed to save mapping';
			console.error('Save error:', err);
		} finally {
			isSaving = false;
		}
	}

	// Watch URL params on initial load
	$effect(() => {
		const spec = $page.url.searchParams.get('spec');
		if (spec && spec !== datasetSpec) {
			datasetSpec = spec;
			loadSourceData(spec);
		}
	});

	// Load target rec-def on mount
	onMount(() => {
		loadTargetData(mappingPrefix);
	});

	// Track mappings from store
	let currentMappings = $state<Mapping[]>([]);

	// Track if user has manually edited the code (to avoid overwriting)
	let userHasEdited = $state(false);

	// Groovy code state - starts empty, will be populated by mappings
	let groovyCode = $state(generateGroovyCode([], datasetSpec ?? 'dataset', mappingPrefix));

	// Server-side code loading state
	let isLoadingServerCode = $state(false);
	let serverCodeError = $state<string | null>(null);
	let serverCode = $state<string | null>(null);

	// Subscribe to mappings store
	onMount(() => {
		const unsubscribe = mappingsStore.subscribe((mappings) => {
			currentMappings = mappings;
		});

		return unsubscribe;
	});

	// Load full Groovy code from server
	async function loadServerGroovyCode() {
		if (!datasetSpec) return;

		isLoadingServerCode = true;
		serverCodeError = null;

		try {
			// Get any edited codes from the store
			const editedCodes = editedCodeStore.getAllEdited();

			const response = await generateFullGroovyCode(datasetSpec, editedCodes);
			if (response.success) {
				serverCode = response.code;
				groovyCode = response.code;
			} else {
				serverCodeError = 'Failed to generate code from server';
			}
		} catch (err) {
			serverCodeError = err instanceof Error ? err.message : 'Failed to load code';
			// Fall back to client-side generation
			groovyCode = generateGroovyCode(currentMappings, datasetSpec ?? 'dataset', mappingPrefix);
		} finally {
			isLoadingServerCode = false;
		}
	}

	// Load server code when dataset changes or when Full Code tab is activated
	$effect(() => {
		if (datasetSpec && activeCodeTab === 'full') {
			loadServerGroovyCode();
		}
	});

	// Auto-generate code when mappings change (using $effect for proper reactivity)
	// Only when not using real data
	$effect(() => {
		const mappings = currentMappings;
		const spec = datasetSpec ?? 'dataset';
		if (!useRealData && (!userHasEdited || mappings.length === 0)) {
			groovyCode = generateGroovyCode(mappings, spec, mappingPrefix);
		}
	});

	function handleCodeChange(newCode: string) {
		// Full Code tab is read-only when using real data
		if (useRealData) return;

		// Mark as user-edited if the change came from manual editing
		// We detect this by checking if the code differs from what would be generated
		const spec = datasetSpec ?? 'dataset';
		const generatedCode = generateGroovyCode(currentMappings, spec, mappingPrefix);
		if (newCode !== generatedCode) {
			userHasEdited = true;
		}
		groovyCode = newCode;
	}

	// Regenerate code button handler
	function regenerateCode() {
		userHasEdited = false;
		if (useRealData && datasetSpec) {
			loadServerGroovyCode();
		} else {
			groovyCode = generateGroovyCode(currentMappings, datasetSpec ?? 'dataset', mappingPrefix);
		}
	}

	// Selection state
	let selectedSourceId = $state<string | null>(null);
	let selectedTargetId = $state<string | null>(null);
	let selectedNode = $state<TreeNode | null>(null);
	let selectedType = $state<'source' | 'target'>('source');

	// Pane refs for keyboard navigation
	let sourcePaneRef: HTMLElement;
	let targetPaneRef: HTMLElement;
	let codePaneRef: HTMLElement;
	let previewPaneRef: HTMLElement;

	// Track Ctrl key state to show panel shortcuts
	let ctrlHeld = $state(false);

	// Focus a pane and find first focusable element
	function focusPane(paneRef: HTMLElement | undefined, paneName: string) {
		if (!paneRef) return;

		// Special handling for different panes
		if (paneName === 'Code Editor') {
			// For code editor, focus Monaco if in full code mode, or first mapping in tweak
			const monacoEditor = paneRef.querySelector('.monaco-editor textarea, .monaco-editor [contenteditable="true"]') as HTMLElement;
			if (monacoEditor && activeCodeTab === 'full') {
				monacoEditor.focus();
				return;
			}
			// In tweak mode, focus the first mapping item
			const firstMapping = paneRef.querySelector('.mapping-item') as HTMLElement;
			if (firstMapping) {
				firstMapping.focus();
				return;
			}
		}

		if (paneName === 'Preview') {
			// For preview, open search and focus it
			const searchToggle = paneRef.querySelector('.search-toggle') as HTMLElement;
			if (searchToggle) {
				searchToggle.click();
				// Focus the search input after it opens
				setTimeout(() => {
					const searchInput = paneRef.querySelector('input[type="text"]') as HTMLElement;
					if (searchInput) searchInput.focus();
				}, 50);
				return;
			}
		}

		// Default: Try to focus the search input first, then any focusable element
		const searchInput = paneRef.querySelector('input[type="text"]') as HTMLElement;
		const firstFocusable = paneRef.querySelector('button, [tabindex="0"], input, textarea') as HTMLElement;

		if (searchInput) {
			searchInput.focus();
		} else if (firstFocusable) {
			firstFocusable.focus();
		} else {
			paneRef.focus();
		}
	}

	// Code editor tab state - default to 'tweak' for quick editing
	let activeCodeTab = $state<'full' | 'tweak'>('tweak');

	// Shared record index for preview and tweak panel
	let currentRecordIndex = $state(0);

	// Selected mapping ID for tweak panel
	let selectedMappingId = $state<string | null>(null);

	// Mapping modal state
	let modalOpen = $state(false);
	let modalSourceNode = $state<TreeNode | null>(null);
	let modalSourceTreeType = $state<'source' | 'target'>('source');

	// Handler for when "+" button is clicked on a tree node
	function handleAddClick(node: TreeNode, treeType: 'source' | 'target') {
		modalSourceNode = node;
		modalSourceTreeType = treeType;
		modalOpen = true;
	}

	// Handler for when mapping is created from modal
	function handleModalCreateMapping(sourceNode: TreeNode, targetNode: TreeNode) {
		// The modal's sourceNode is always the node where "+" was clicked
		// The targetNode is what was selected in the modal (from the opposite tree)
		if (modalSourceTreeType === 'source') {
			// Clicked + on source tree, selected target in modal
			mappingsStore.addMapping(sourceNode, targetNode);
		} else {
			// Clicked + on target tree, selected source in modal
			mappingsStore.addMapping(targetNode, sourceNode);
		}
	}

	// Close modal handler
	function handleModalClose() {
		modalOpen = false;
		modalSourceNode = null;
	}

	// Code edit modal state
	let codeEditModalOpen = $state(false);
	let codeEditMapping = $state<Mapping | null>(null);
	let customMappingCode = $state<Record<string, string>>({});

	// Preview modal state
	let previewModalOpen = $state(false);

	function openPreviewModal() {
		previewModalOpen = true;
	}

	function closePreviewModal() {
		previewModalOpen = false;
	}

	// Handler for when edit button is clicked on a mapping
	function handleEditClick(mapping: Mapping) {
		codeEditMapping = mapping;
		codeEditModalOpen = true;
	}

	// Handler for saving code from the modal
	function handleCodeEditSave(mappingId: string, code: string) {
		customMappingCode = { ...customMappingCode, [mappingId]: code };
	}

	// Close code edit modal handler
	function handleCodeEditModalClose() {
		codeEditModalOpen = false;
		codeEditMapping = null;
	}

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

	// Navigate to a node by path (used by StatisticsPanel breadcrumb)
	function handleNavigateToPath(path: string) {
		const node = findNodeByPath(sourceNodes, path);
		if (node) {
			handleSourceSelect(node);
			// Scroll the node into view in the tree
			setTimeout(() => {
				const nodeElement = document.querySelector(`[data-node-path="${path}"]`);
				if (nodeElement) {
					nodeElement.scrollIntoView({ behavior: 'smooth', block: 'center' });
				}
			}, 50);
		}
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

<svelte:window onkeydown={handleGlobalKeydown} onkeyup={handleGlobalKeyup} onblur={handleWindowBlur} />

<!-- Header -->
<header class="flex items-center justify-between px-4 py-2 bg-gray-800 border-b border-gray-700">
	<div class="flex items-center gap-4">
		<h1 class="text-lg font-semibold">Mapping Editor</h1>
		<DatasetPicker selectedSpec={datasetSpec} onSelect={handleDatasetSelect} />
		<span class="text-gray-400 flex items-center gap-2">
			/ {mappingPrefix}
			{#if useRealData}
				<span class="px-1.5 py-0.5 text-[10px] bg-green-800 text-green-200 rounded">LIVE</span>
			{:else}
				<span class="px-1.5 py-0.5 text-[10px] bg-yellow-800 text-yellow-200 rounded">SAMPLE</span>
			{/if}
		</span>
	</div>
	<div class="flex items-center gap-2">
		<!-- Mode toggle -->
		<div class="flex items-center bg-gray-700/50 rounded-lg p-0.5">
			<button
				class="px-3 py-1.5 text-xs font-medium rounded-md transition-all"
				class:bg-gray-700={editorMode === 'mapping'}
				class:text-white={editorMode === 'mapping'}
				class:text-gray-400={editorMode !== 'mapping'}
				class:hover:text-gray-200={editorMode !== 'mapping'}
				onclick={() => editorMode = 'mapping'}
			>
				<span class="flex items-center gap-1.5">
					<svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
						<path d="M8 3H5a2 2 0 00-2 2v3m18 0V5a2 2 0 00-2-2h-3M3 16v3a2 2 0 002 2h3m8 0h3a2 2 0 002-2v-3"/>
						<path d="M10 8l4 4m0-4l-4 4"/>
					</svg>
					Mapping
				</span>
			</button>
			<button
				class="px-3 py-1.5 text-xs font-medium rounded-md transition-all"
				class:bg-blue-600={editorMode === 'stats'}
				class:text-white={editorMode === 'stats'}
				class:text-gray-400={editorMode !== 'stats'}
				class:hover:text-gray-200={editorMode !== 'stats'}
				onclick={() => editorMode = 'stats'}
			>
				<span class="flex items-center gap-1.5">
					<svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
						<path d="M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 013 19.875v-6.75zM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V8.625zM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V4.125z"/>
					</svg>
					Statistics
				</span>
			</button>
		</div>
		<div class="w-px h-6 bg-gray-600"></div>
		<!-- Tour button (placeholder) -->
		<button
			class="px-2 py-1 text-xs text-gray-500 bg-gray-800 rounded flex items-center gap-1 cursor-not-allowed opacity-60"
			disabled
			title="Guided tour coming soon"
		>
			<svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
				<circle cx="12" cy="12" r="10"/>
				<path d="M12 16v-4M12 8h.01"/>
			</svg>
			<span>Take Tour</span>
		</button>
		<button
			class="px-2 py-1 text-xs text-gray-400 hover:text-gray-200 hover:bg-gray-700 rounded flex items-center gap-1"
			onclick={toggleShortcutsOverlay}
			title="Keyboard shortcuts"
		>
			<kbd class="px-1.5 py-0.5 text-[10px] bg-gray-700 rounded border border-gray-600">?</kbd>
			<span>Shortcuts</span>
		</button>
		<ThemeToggle />
		<button class="px-3 py-1.5 bg-gray-700 hover:bg-gray-600 rounded text-sm">
			Revert
		</button>
		<!-- Save status messages -->
		{#if saveSuccess}
			<span class="px-2 py-1 text-xs bg-green-800/50 text-green-300 rounded flex items-center gap-1">
				<svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
					<path d="M20 6L9 17l-5-5"/>
				</svg>
				{saveSuccess}
			</span>
		{/if}
		{#if saveError}
			<span class="px-2 py-1 text-xs bg-red-800/50 text-red-300 rounded flex items-center gap-1">
				<svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
					<circle cx="12" cy="12" r="10"/>
					<path d="M12 8v4M12 16h.01"/>
				</svg>
				{saveError}
			</span>
		{/if}
		<button
			class="px-3 py-1.5 bg-blue-600 hover:bg-blue-700 disabled:bg-blue-800 disabled:opacity-50 disabled:cursor-not-allowed rounded text-sm font-medium flex items-center gap-2"
			onclick={handleSave}
			disabled={isSaving || !datasetSpec}
			title={!datasetSpec ? 'Select a dataset first' : isSaving ? 'Saving...' : 'Save mapping to server'}
		>
			{#if isSaving}
				<svg class="w-4 h-4 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
					<path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83"/>
				</svg>
			{:else}
				<svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
					<path d="M19 21H5a2 2 0 01-2-2V5a2 2 0 012-2h11l5 5v11a2 2 0 01-2 2z"/>
					<polyline points="17,21 17,13 7,13 7,21"/>
					<polyline points="7,3 7,8 15,8"/>
				</svg>
			{/if}
			{isSaving ? 'Saving...' : 'Save Mapping'}
		</button>
	</div>
</header>

<!-- Main Content -->
<main class="h-[calc(100vh-48px)]">
	{#if editorMode === 'stats'}
		<!-- STATISTICS MODE: Full height for source tree + statistics panel -->
		<div class="h-full">
			<PaneGroup direction="horizontal">
				<!-- Source Tree Panel (wider in full stats mode) -->
				<Pane defaultSize={35} minSize={25}>
					<div class="h-full flex flex-col bg-gray-900 border-r border-gray-700" bind:this={sourcePaneRef}>
						<div class="px-3 py-2 bg-gray-800 border-b border-gray-700 flex items-center justify-between">
							<span class="text-sm font-medium flex items-center gap-2">
								Source Structure
								{#if orphanPaths.size > 0}
									<button
										class="flex items-center gap-1 px-1.5 py-0.5 text-[10px] bg-amber-900/50 text-amber-300 rounded hover:bg-amber-800/50 transition-colors"
										onclick={() => { missingPathsModalType = 'source'; missingPathsModalOpen = true; }}
										title="Click to view orphan source paths"
									>
										<svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
											<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
										</svg>
										{orphanPaths.size} orphans
									</button>
								{/if}
							</span>
							<span class="text-xs text-gray-500 flex items-center gap-1">
								{#if ctrlHeld}
									<kbd class="px-1.5 py-0.5 text-[10px] bg-blue-600 text-white rounded font-bold animate-pulse">1</kbd>
								{/if}
								XML
								{#if useRealData}
									<span class="px-1.5 py-0.5 text-[10px] bg-green-800 text-green-200 rounded">LIVE</span>
								{:else}
									<span class="px-1.5 py-0.5 text-[10px] bg-yellow-800 text-yellow-200 rounded">SAMPLE</span>
								{/if}
							</span>
						</div>
						<div class="flex-1 overflow-auto">
							{#if isLoadingSource}
								<div class="flex items-center justify-center h-full text-gray-400">
									<svg class="animate-spin h-5 w-5 mr-2" viewBox="0 0 24 24">
										<circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" fill="none"/>
										<path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/>
									</svg>
									Loading source structure...
								</div>
							{:else if sourceError}
								<div class="p-4 text-center">
									<div class="text-red-400 mb-2">{sourceError}</div>
									<div class="text-xs text-gray-500">Using sample data</div>
								</div>
							{:else}
								<SourceTree
									nodes={sourceNodes}
									selectedId={selectedSourceId}
									treeType="source"
									onSelect={handleSourceSelect}
									onMappingClick={handleMappingClick}
									onAddClick={handleAddClick}
								/>
							{/if}
						</div>
					</div>
				</Pane>

				<PaneResizer class="w-1 bg-gray-700 hover:bg-blue-500 transition-colors cursor-col-resize" />

				<!-- Full Statistics Panel -->
				<Pane defaultSize={65} minSize={40}>
					<div class="h-full">
						<StatisticsPanel
							node={selectedType === 'source' ? selectedNode : null}
							datasetSpec={datasetSpec}
							onNavigateToPath={handleNavigateToPath}
						/>
					</div>
				</Pane>
			</PaneGroup>
		</div>
	{:else}
		<!-- MAPPING MODE: Source tree, target tree, code editor, preview -->
		<PaneGroup direction="vertical">
			<!-- Top section: Trees and connections -->
			<Pane defaultSize={60} minSize={30}>
				<PaneGroup direction="horizontal">
					<!-- Source Tree Panel -->
					<Pane defaultSize={35} minSize={20}>
						<div class="h-full flex flex-col bg-gray-900 border-r border-gray-700" bind:this={sourcePaneRef}>
							<div class="px-3 py-2 bg-gray-800 border-b border-gray-700 flex items-center justify-between">
								<span class="text-sm font-medium flex items-center gap-2">
									Source Structure
									{#if orphanPaths.size > 0}
										<button
											class="flex items-center gap-1 px-1.5 py-0.5 text-[10px] bg-amber-900/50 text-amber-300 rounded hover:bg-amber-800/50 transition-colors"
											onclick={() => { missingPathsModalType = 'source'; missingPathsModalOpen = true; }}
											title="Click to view orphan source paths"
										>
											<svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
											</svg>
											{orphanPaths.size} orphans
										</button>
									{/if}
								</span>
								<span class="text-xs text-gray-500 flex items-center gap-1">
									{#if ctrlHeld}
										<kbd class="px-1.5 py-0.5 text-[10px] bg-blue-600 text-white rounded font-bold animate-pulse">1</kbd>
									{/if}
									XML
									{#if useRealData}
										<span class="px-1.5 py-0.5 text-[10px] bg-green-800 text-green-200 rounded">LIVE</span>
									{:else}
										<span class="px-1.5 py-0.5 text-[10px] bg-yellow-800 text-yellow-200 rounded">SAMPLE</span>
									{/if}
								</span>
							</div>
							<div class="flex-1 overflow-auto">
								{#if isLoadingSource}
									<div class="flex items-center justify-center h-full text-gray-400">
										<svg class="animate-spin h-5 w-5 mr-2" viewBox="0 0 24 24">
											<circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" fill="none"/>
											<path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/>
										</svg>
										Loading source structure...
									</div>
								{:else if sourceError}
									<div class="p-4 text-center">
										<div class="text-red-400 mb-2">{sourceError}</div>
										<div class="text-xs text-gray-500">Using sample data</div>
									</div>
								{:else}
									<SourceTree
										nodes={sourceNodes}
										selectedId={selectedSourceId}
										treeType="source"
										onSelect={handleSourceSelect}
										onMappingClick={handleMappingClick}
										onAddClick={handleAddClick}
									/>
								{/if}
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
								<FieldStats
									node={selectedNode}
									type={selectedType}
									datasetSpec={datasetSpec}
									onNavigateToPath={handleNavigateToPath}
								/>
							</div>
						</div>
					</Pane>

					<PaneResizer class="w-1 bg-gray-700 hover:bg-blue-500 transition-colors cursor-col-resize" />

					<!-- Target Tree Panel -->
					<Pane defaultSize={35} minSize={20}>
						<div class="h-full flex flex-col bg-gray-900 border-l border-gray-700" bind:this={targetPaneRef}>
							<div class="px-3 py-2 bg-gray-800 border-b border-gray-700 flex items-center justify-between">
								<span class="text-sm font-medium flex items-center gap-2">
									Target Schema
									{#if targetOrphanPaths.size > 0}
										<button
											class="flex items-center gap-1 px-1.5 py-0.5 text-[10px] bg-amber-900/50 text-amber-300 rounded hover:bg-amber-800/50 transition-colors"
											onclick={() => { missingPathsModalType = 'target'; missingPathsModalOpen = true; }}
											title="Click to view missing target paths"
										>
											<svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
											</svg>
											{targetOrphanPaths.size} missing
										</button>
									{/if}
								</span>
								<span class="text-xs text-gray-500 flex items-center gap-1">
									{#if ctrlHeld}
										<kbd class="px-1.5 py-0.5 text-[10px] bg-blue-600 text-white rounded font-bold animate-pulse">2</kbd>
									{/if}
									{mappingPrefix.toUpperCase()}
									{#if useRealTargetData}
										<span class="px-1.5 py-0.5 text-[10px] bg-green-800 text-green-200 rounded">LIVE</span>
									{:else}
										<span class="px-1.5 py-0.5 text-[10px] bg-yellow-800 text-yellow-200 rounded">SAMPLE</span>
									{/if}
								</span>
							</div>
							<div class="flex-1 overflow-auto">
								{#if isLoadingTarget}
									<div class="flex items-center justify-center h-full text-gray-400">
										<svg class="animate-spin h-5 w-5 mr-2" viewBox="0 0 24 24">
											<circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" fill="none"/>
											<path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"/>
										</svg>
										Loading target schema...
									</div>
								{:else if targetError}
									<div class="p-4 text-center">
										<div class="text-red-400 mb-2">{targetError}</div>
										<div class="text-xs text-gray-500">Using sample data</div>
									</div>
								{:else}
									<SourceTree
										nodes={targetNodes}
										selectedId={selectedTargetId}
										treeType="target"
										onSelect={handleTargetSelect}
										onMappingClick={handleMappingClick}
										onAddClick={handleAddClick}
										compactMode={true}
									/>
								{/if}
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
					<div class="h-full flex flex-col bg-gray-900 border-r border-gray-700" bind:this={codePaneRef}>
						<!-- Tab header -->
						<div class="px-3 py-2 bg-gray-800 border-b border-gray-700 flex items-center justify-between">
							<div class="flex items-center gap-1">
								<button
									class="px-3 py-1 text-sm rounded-t transition-colors flex items-center gap-1"
									class:bg-gray-900={activeCodeTab === 'tweak'}
									class:text-white={activeCodeTab === 'tweak'}
									class:bg-transparent={activeCodeTab !== 'tweak'}
									class:text-gray-400={activeCodeTab !== 'tweak'}
									class:hover:text-gray-200={activeCodeTab !== 'tweak'}
									onclick={() => activeCodeTab = 'tweak'}
								>
									Tweak
									{#if $hasUnsavedEdits}
										<span class="text-[9px] px-1 py-0.5 bg-yellow-600 text-yellow-100 rounded">{$modifiedCount} edited</span>
									{/if}
								</button>
								<button
									class="px-3 py-1 text-sm rounded-t transition-colors flex items-center gap-1"
									class:bg-gray-900={activeCodeTab === 'full'}
									class:text-white={activeCodeTab === 'full'}
									class:bg-transparent={activeCodeTab !== 'full'}
									class:text-gray-400={activeCodeTab !== 'full'}
									class:hover:text-gray-200={activeCodeTab !== 'full'}
									onclick={() => activeCodeTab = 'full'}
								>
									Full Code
									{#if useRealData}
										<span class="text-[9px] px-1 py-0.5 bg-gray-600 text-gray-300 rounded">read-only</span>
									{/if}
								</button>
								{#if activeCodeTab === 'full'}
									{#if !useRealData && userHasEdited}
										<span class="text-xs text-yellow-500 ml-2">(edited)</span>
									{/if}
									{#if currentMappings.length > 0}
										<span class="text-xs text-gray-500 ml-1">({currentMappings.length} mappings)</span>
									{/if}
								{/if}
							</div>
							<div class="flex items-center gap-2">
								{#if ctrlHeld}
									<kbd class="px-1.5 py-0.5 text-[10px] bg-blue-600 text-white rounded font-bold animate-pulse">3</kbd>
								{/if}
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
						<div class="flex-1 overflow-hidden relative">
							{#if activeCodeTab === 'full'}
								{#if isLoadingServerCode}
									<div class="absolute inset-0 flex items-center justify-center bg-gray-900/50 z-10">
										<div class="flex items-center gap-2 text-gray-400">
											<div class="w-4 h-4 border-2 border-gray-400 border-t-blue-500 rounded-full animate-spin"></div>
											<span>Loading Groovy code...</span>
										</div>
									</div>
								{/if}
								{#if serverCodeError}
									<div class="absolute top-2 left-2 right-2 bg-red-900/80 text-red-200 px-3 py-2 rounded text-sm z-10">
										{serverCodeError}
									</div>
								{/if}
								<CodeEditor value={groovyCode} onChange={handleCodeChange} readonly={useRealData} />
							{:else}
								<CodeTweakPanel
									datasetSpec={datasetSpec}
									selectedMappingId={selectedMappingId}
									onMappingSelect={handleMappingSelect}
									onEditClick={handleEditClick}
								/>
							{/if}
						</div>
					</div>
				</Pane>

				<PaneResizer class="w-1 bg-gray-700 hover:bg-blue-500 transition-colors cursor-col-resize" />

				<!-- Preview Panel -->
				<Pane defaultSize={50} minSize={30}>
					<div class="h-full" bind:this={previewPaneRef}>
						<Preview
							spec={useRealData ? datasetSpec ?? undefined : undefined}
							groovyCode={groovyCode}
							mappings={currentMappings}
							currentRecordIndex={currentRecordIndex}
							onRecordChange={handleRecordChange}
							showShortcutHint={ctrlHeld}
							totalRecordCount={totalRecordCount}
							onMaximize={openPreviewModal}
						/>
					</div>
				</Pane>
			</PaneGroup>
		</Pane>
	</PaneGroup>
	{/if}
</main>

<!-- Mapping Modal -->
<MappingModal
	isOpen={modalOpen}
	sourceNode={modalSourceNode}
	sourceTreeType={modalSourceTreeType}
	sourceTree={sourceNodes}
	targetTreeData={targetNodes}
	onClose={handleModalClose}
	onCreateMapping={handleModalCreateMapping}
/>

<!-- Code Edit Modal -->
<CodeEditModal
	isOpen={codeEditModalOpen}
	mapping={codeEditMapping}
	currentRecordIndex={currentRecordIndex}
	customCode={codeEditMapping ? customMappingCode[codeEditMapping.id] : undefined}
	customFunctions={sampleCustomFunctions}
	customVariables={sampleCustomVariables}
	datasetSpec={datasetSpec}
	totalRecords={totalRecordCount}
	onClose={handleCodeEditModalClose}
	onSave={handleCodeEditSave}
	onRecordChange={handleRecordChange}
/>

<!-- Preview Modal (Fullscreen) -->
<PreviewModal
	isOpen={previewModalOpen}
	onClose={closePreviewModal}
	spec={useRealData ? datasetSpec ?? undefined : undefined}
	mappings={currentMappings}
	currentRecordIndex={currentRecordIndex}
	onRecordChange={handleRecordChange}
	totalRecordCount={totalRecordCount}
/>

<!-- Constant Input Modal -->
{#if constantModalOpen}
	<div class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" onclick={closeConstantModal} onkeydown={(e) => e.key === 'Escape' && closeConstantModal()} role="dialog" tabindex="-1">
		<div class="bg-gray-800 rounded-lg shadow-xl p-4 w-96" onclick={(e) => e.stopPropagation()} onkeydown={(e) => e.stopPropagation()} role="document">
			<h3 class="text-lg font-medium mb-3">Enter Constant Value</h3>
			<p class="text-sm text-gray-400 mb-3">
				Map constant text to: <span class="text-cyan-400 font-mono">{constantModalTarget?.name}</span>
			</p>
			<input
				type="text"
				class="w-full px-3 py-2 bg-gray-900 border border-gray-600 rounded text-sm focus:border-blue-500 focus:outline-none"
				placeholder="Enter constant value..."
				bind:value={constantValue}
				onkeydown={(e) => e.key === 'Enter' && submitConstantMapping()}
			/>
			<div class="flex justify-end gap-2 mt-4">
				<button
					type="button"
					class="px-3 py-1.5 text-sm text-gray-400 hover:text-white"
					onclick={closeConstantModal}
				>
					Cancel
				</button>
				<button
					type="button"
					class="px-3 py-1.5 text-sm bg-blue-600 hover:bg-blue-700 rounded"
					onclick={submitConstantMapping}
				>
					Add Constant
				</button>
			</div>
		</div>
	</div>
{/if}

<!-- Missing Paths Modal -->
{#if missingPathsModalOpen}
	<div
		class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
		onclick={() => missingPathsModalOpen = false}
		onkeydown={(e) => e.key === 'Escape' && (missingPathsModalOpen = false)}
		role="dialog"
		tabindex="-1"
	>
		<div
			class="bg-gray-800 rounded-lg shadow-xl p-4 w-[600px] max-h-[80vh] flex flex-col"
			onclick={(e) => e.stopPropagation()}
			onkeydown={(e) => e.stopPropagation()}
			role="document"
		>
			<div class="flex items-center justify-between mb-4">
				<h3 class="text-lg font-medium flex items-center gap-2">
					<svg class="w-5 h-5 text-amber-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
					</svg>
					{#if missingPathsModalType === 'source'}
						Orphan Source Paths ({orphanPaths.size})
					{:else}
						Missing Target Paths ({targetOrphanPaths.size})
					{/if}
				</h3>
				<button
					class="text-gray-400 hover:text-white p-1 rounded hover:bg-gray-700"
					onclick={() => missingPathsModalOpen = false}
					title="Close modal"
				>
					<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/>
					</svg>
				</button>
			</div>

			<p class="text-sm text-gray-400 mb-4">
				{#if missingPathsModalType === 'source'}
					These source paths are referenced in mappings but don't exist in the current source structure.
					They may be from a different record or the source data has changed.
				{:else}
					These target paths are referenced in mappings but don't exist in the current rec-def schema.
					The schema may need to be updated, or these are legacy mappings.
				{/if}
			</p>

			<div class="flex-1 overflow-auto bg-gray-900 rounded border border-gray-700 p-2 font-mono text-sm">
				{#if missingPathsModalType === 'source'}
					{#each [...orphanPaths].sort() as path}
						<div class="py-1 px-2 hover:bg-gray-800 rounded text-orange-300 break-all">
							{path}
						</div>
					{/each}
				{:else}
					{#each [...targetOrphanPaths].sort() as path}
						<div class="py-1 px-2 hover:bg-gray-800 rounded text-amber-300 break-all">
							{path}
						</div>
					{/each}
				{/if}
			</div>

			<div class="flex justify-end mt-4">
				<button
					type="button"
					class="px-4 py-2 text-sm bg-gray-700 hover:bg-gray-600 rounded"
					onclick={() => missingPathsModalOpen = false}
				>
					Close
				</button>
			</div>
		</div>
	</div>
{/if}

<!-- Keyboard Shortcuts Overlay -->
<KeyboardShortcutsOverlay
	open={shortcutsOverlayOpen}
	onClose={() => shortcutsOverlayOpen = false}
/>

<!-- Command Palette -->
<CommandPalette
	open={commandPaletteOpen}
	onClose={() => commandPaletteOpen = false}
	commands={commands}
/>
