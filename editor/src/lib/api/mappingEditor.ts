// API service for mapping editor
// Connects Svelte frontend to Narthex backend

import type { TreeNode, FieldQuality, LengthDistribution, FieldStatus, QualitySummary } from '$lib/types';
import type { TypeInfo, PatternInfo } from '$lib/types';

// Base URL for Narthex API
const API_BASE = '/narthex/app';

// Dataset info from Narthex
export interface DatasetInfo {
	spec: string;
	name: string | null;
	sourceRecordCount: number | null;
	recordCount: number | null;
	state: string | null;
}

/**
 * Load list of available datasets
 * Uses existing Narthex endpoint: GET /narthex/app/dataset-list-light
 */
export async function loadDatasets(): Promise<DatasetInfo[]> {
	const response = await fetch(`${API_BASE}/dataset-list-light`);

	if (!response.ok) {
		throw new Error(`Failed to load datasets: ${response.statusText}`);
	}

	const data = await response.json();

	// Transform to our simplified type
	return data.map((ds: any) => ({
		spec: ds.spec,
		name: ds.name || ds.spec,
		sourceRecordCount: ds.sourceRecordCount,
		recordCount: ds.recordCount,
		state: ds.state
	}));
}

// Server source index format (from Narthex TreeNode.scala)
interface ServerTreeNode {
	tag: string;
	path: string;
	count: number;
	lengths: [string, string][]; // [range, count] pairs
	quality?: {
		totalRecords: number;
		recordsWithValue: number;
		emptyCount: number;
		completeness: number;
		avgPerRecord: number;
		emptyRate: number;
	};
	kids: ServerTreeNode[];
}

/**
 * Transform server tree node to client TreeNode format
 */
function transformSourceNode(node: ServerTreeNode): TreeNode {
	const isAttribute = node.tag.startsWith('@');
	const hasValues = node.lengths && node.lengths.length > 0;

	// Transform lengths array to LengthDistribution[]
	const lengths: LengthDistribution[] | undefined = node.lengths?.map(([range, count]) => ({
		range,
		count: parseInt(count, 10)
	}));

	// Transform quality data
	const quality: FieldQuality | undefined = node.quality ? {
		totalRecords: node.quality.totalRecords,
		recordsWithValue: node.quality.recordsWithValue,
		emptyCount: node.quality.emptyCount,
		completeness: node.quality.completeness,
		avgPerRecord: node.quality.avgPerRecord,
		emptyRate: node.quality.emptyRate
	} : undefined;

	return {
		id: node.path.replace(/\//g, '_').replace(/^_/, ''), // /record/title -> record_title
		name: node.tag,
		path: node.path,
		count: node.count,
		isAttribute,
		hasValues,
		children: node.kids?.map(transformSourceNode),
		quality,
		lengths
	};
}

/**
 * Load source tree structure for a dataset
 * Uses existing Narthex endpoint: GET /narthex/app/dataset/:spec/source-index
 *
 * Returns structure matching SIP-Creator:
 * - pockets (root)
 *   - _constant (for direct text mapping)
 *   - _facts (placeholder for mapping facts, populated separately)
 *   - pocket
 *     - @id
 *     - record
 *       - metadata
 *         - dc
 *           - title (path: /pockets/pocket/record/metadata/dc/title)
 *
 * Mapping inputPath example: /record/metadata/dc/title (without /pockets/pocket prefix)
 * Target outputPath example: /RDF/ProvidedCHO/dc:title
 */
export async function loadSourceTree(spec: string): Promise<TreeNode[]> {
	const response = await fetch(`${API_BASE}/dataset/${spec}/source-index`);

	if (!response.ok) {
		if (response.status === 404) {
			throw new Error('Source not analyzed. Please analyze the dataset first.');
		}
		throw new Error(`Failed to load source tree: ${response.statusText}`);
	}

	const serverNode: ServerTreeNode = await response.json();
	const rootNode = transformSourceNode(serverNode);

	// Create constant node for direct text mapping
	// Uses /constant to match mapping file convention
	const constantNode: TreeNode = {
		id: 'constant',
		name: 'constant',
		path: '/constant',
		isAttribute: false,
		hasValues: true,
		isConstant: true,
		count: 0
	};

	// Create placeholder for facts (will be populated after mapping loads)
	// Uses /facts to match mapping file convention (e.g., /facts/provider)
	const factsNode: TreeNode = {
		id: 'facts',
		name: 'facts',
		path: '/facts',
		isAttribute: false,
		hasValues: false,
		isFacts: true,
		count: 0,
		children: [] // Will be populated with fact entries
	};

	// Structure: pockets > [_constant, _facts, pocket, ...]
	// Insert constant and facts at the beginning of pockets' children
	if (rootNode.children) {
		rootNode.children = [constantNode, factsNode, ...rootNode.children];
	} else {
		rootNode.children = [constantNode, factsNode];
	}

	// Return pockets as the root (matching SIP-Creator)
	return [rootNode];
}

// Sample response format from Narthex
interface SampleResponse {
	sample: string[];
}

/**
 * Load sample records for preview
 * Uses existing Narthex endpoint: GET /narthex/app/dataset/:spec/source-sample/:size/*path
 * Note: Narthex only generates sample-100.json files, so size 100 is the only available option
 */
export async function loadSampleRecords(spec: string, path: string, size: number = 100): Promise<string[]> {
	const encodedPath = path.startsWith('/') ? path : `/${path}`;
	const response = await fetch(`${API_BASE}/dataset/${spec}/source-sample/${size}${encodedPath}`);

	if (!response.ok) {
		throw new Error(`Failed to load sample records: ${response.statusText}`);
	}

	// Narthex returns { sample: [...] }
	const data: SampleResponse = await response.json();
	return data.sample || [];
}

// Histogram response format from Narthex
export interface HistogramResponse {
	tag: string;
	uri: string;
	uniqueCount: number;
	entries: number;
	maximum: number;
	complete: boolean;
	histogram: [string, string][]; // [count, value] pairs as strings
}

// Parsed histogram data with metadata
export interface HistogramData {
	values: { value: string; count: number }[];
	uniqueCount: number;
	entries: number;
	currentSize: number;
	complete: boolean;
}

// Available bracket sizes in Narthex
export const HISTOGRAM_BRACKETS = [100, 500, 2500, 12500] as const;

/**
 * Get the next available bracket size
 */
export function getNextBracketSize(currentSize: number): number | null {
	const idx = HISTOGRAM_BRACKETS.indexOf(currentSize as typeof HISTOGRAM_BRACKETS[number]);
	if (idx >= 0 && idx < HISTOGRAM_BRACKETS.length - 1) {
		return HISTOGRAM_BRACKETS[idx + 1];
	}
	return null;
}

/**
 * Load histogram for a field with full metadata
 * Uses existing Narthex endpoint: GET /narthex/app/dataset/:spec/source-histogram/:size/*path
 */
export async function loadHistogram(spec: string, path: string, size: number = 100): Promise<HistogramData> {
	const encodedPath = path.startsWith('/') ? path : `/${path}`;
	const response = await fetch(`${API_BASE}/dataset/${spec}/source-histogram/${size}${encodedPath}`);

	if (!response.ok) {
		throw new Error(`Failed to load histogram: ${response.statusText}`);
	}

	// Narthex returns { histogram: [[count, value], ...], uniqueCount, entries, maximum, complete }
	const data: HistogramResponse = await response.json();

	// Convert [count, value] pairs to array of {value, count}
	const values = (data.histogram || []).map(([countStr, value]) => ({
		value,
		count: parseInt(countStr, 10)
	}));

	return {
		values,
		uniqueCount: data.uniqueCount,
		entries: data.entries,
		currentSize: data.maximum,
		complete: data.complete
	};
}

/**
 * Get download URL for raw histogram text file
 * Uses: GET /narthex/api/:spec/histogram/*path
 */
export function getHistogramDownloadUrl(spec: string, path: string): string {
	const encodedPath = path.startsWith('/') ? path : `/${path}`;
	return `${API_BASE.replace('/app', '/api')}/${spec}/histogram${encodedPath}`;
}

// Server status.json format (from Narthex)
interface ServerFieldStatus {
	uniqueCount?: number;
	typeInfo?: {
		dominantType?: string;
		consistency?: number;
		isMixed?: boolean;
		distribution?: Record<string, number>;
	};
	patternInfo?: {
		dominantPattern?: string;
		patternConsistency?: number;
		patterns?: Record<string, number>;
		uriValidation?: {
			total?: number;
			valid?: number;
			invalid?: number;
			validRate?: number;
			samples?: string[];
		};
	};
	valueStats?: {
		length?: { min?: number; max?: number; avg?: number };
		wordCount?: { min?: number; max?: number; avg?: number };
		whitespace?: {
			leadingCount?: number;
			trailingCount?: number;
			multipleSpacesCount?: number;
			total?: number;
			samples?: string[];
		};
		encodingIssues?: {
			mojibake?: number;
			htmlEntities?: number;
			escapedChars?: number;
			controlChars?: number;
			replacementChars?: number;
			total?: number;
			samples?: string[];
		};
		outliers?: {
			futureDates?: number;
			ancientDates?: number;
			suspiciousYears?: number;
			total?: number;
			samples?: string[];
		};
		numericRange?: {
			min?: number;
			max?: number;
			count?: number;
		};
	};
}

/**
 * Transform server field status to client FieldStatus format
 */
function transformFieldStatus(server: ServerFieldStatus): FieldStatus {
	const result: FieldStatus = {
		uniqueCount: server.uniqueCount ?? 0
	};

	// Transform typeInfo
	if (server.typeInfo) {
		result.typeInfo = {
			dominantType: (server.typeInfo.dominantType?.toUpperCase() || 'TEXT') as TypeInfo['dominantType'],
			consistency: server.typeInfo.consistency ?? 0,
			isMixed: server.typeInfo.isMixed ?? false,
			distribution: server.typeInfo.distribution ?? {}
		};
	}

	// Transform patternInfo
	if (server.patternInfo) {
		result.patternInfo = {
			dominantPattern: (server.patternInfo.dominantPattern?.toUpperCase() || 'NONE') as PatternInfo['dominantPattern'],
			patternConsistency: server.patternInfo.patternConsistency ?? 0,
			patterns: server.patternInfo.patterns ?? {},
			uriValidation: server.patternInfo.uriValidation ? {
				total: server.patternInfo.uriValidation.total ?? 0,
				valid: server.patternInfo.uriValidation.valid ?? 0,
				invalid: server.patternInfo.uriValidation.invalid ?? 0,
				validRate: server.patternInfo.uriValidation.validRate ?? 0,
				samples: server.patternInfo.uriValidation.samples ?? []
			} : undefined
		};
	}

	// Transform valueStats
	if (server.valueStats) {
		const vs = server.valueStats;
		result.valueStats = {
			length: {
				min: vs.length?.min ?? 0,
				max: vs.length?.max ?? 0,
				avg: vs.length?.avg ?? 0
			},
			wordCount: {
				min: vs.wordCount?.min ?? 0,
				max: vs.wordCount?.max ?? 0,
				avg: vs.wordCount?.avg ?? 0
			}
		};

		// Whitespace issues
		if (vs.whitespace && (vs.whitespace.total ?? 0) > 0) {
			result.valueStats.whitespace = {
				leadingCount: vs.whitespace.leadingCount ?? 0,
				trailingCount: vs.whitespace.trailingCount ?? 0,
				multipleSpacesCount: vs.whitespace.multipleSpacesCount ?? 0,
				total: vs.whitespace.total ?? 0,
				samples: vs.whitespace.samples ?? []
			};
		}

		// Encoding issues
		if (vs.encodingIssues && (vs.encodingIssues.total ?? 0) > 0) {
			result.valueStats.encodingIssues = {
				mojibake: vs.encodingIssues.mojibake ?? 0,
				htmlEntities: vs.encodingIssues.htmlEntities ?? 0,
				escapedChars: vs.encodingIssues.escapedChars ?? 0,
				controlChars: vs.encodingIssues.controlChars ?? 0,
				replacementChars: vs.encodingIssues.replacementChars ?? 0,
				total: vs.encodingIssues.total ?? 0,
				samples: vs.encodingIssues.samples ?? []
			};
		}

		// Outliers
		if (vs.outliers && (vs.outliers.total ?? 0) > 0) {
			result.valueStats.outliers = {
				futureDates: vs.outliers.futureDates ?? 0,
				ancientDates: vs.outliers.ancientDates ?? 0,
				suspiciousYears: vs.outliers.suspiciousYears ?? 0,
				total: vs.outliers.total ?? 0,
				samples: vs.outliers.samples ?? []
			};
		}

		// Numeric range
		if (vs.numericRange && (vs.numericRange.count ?? 0) > 0) {
			result.valueStats.numericRange = {
				min: vs.numericRange.min ?? 0,
				max: vs.numericRange.max ?? 0,
				count: vs.numericRange.count ?? 0
			};
		}
	}

	return result;
}

/**
 * Load field status (type detection, value stats, issues) for a field
 * Uses existing Narthex endpoint: GET /narthex/app/dataset/:spec/source-status/*path
 */
export async function loadFieldStatus(spec: string, path: string): Promise<FieldStatus | null> {
	const encodedPath = path.startsWith('/') ? path : `/${path}`;
	const response = await fetch(`${API_BASE}/dataset/${spec}/source-status${encodedPath}`);

	if (!response.ok) {
		if (response.status === 404) {
			// Status not available for this field
			return null;
		}
		throw new Error(`Failed to load field status: ${response.statusText}`);
	}

	const data: ServerFieldStatus = await response.json();
	return transformFieldStatus(data);
}

/**
 * Load dataset quality summary
 * Uses Narthex endpoint: GET /narthex/app/dataset/:spec/source-quality-summary
 */
export async function loadQualitySummary(spec: string): Promise<QualitySummary | null> {
	const response = await fetch(`${API_BASE}/dataset/${spec}/source-quality-summary`);

	if (!response.ok) {
		if (response.status === 404) {
			// Quality summary not available
			return null;
		}
		throw new Error(`Failed to load quality summary: ${response.statusText}`);
	}

	return response.json();
}

// RecDef response format from Narthex
export interface RecDefResponse {
	prefix: string;
	schemaVersions: string[];
	namespaces: { prefix: string; uri: string }[];
	functions: { name: string; code: string }[];
	tree: RecDefTreeNode[];
}

// Server documentation format from rec-def
interface RecDefDocumentation {
	description?: string;
	paragraphs?: { name: string; content: string }[];
}

// Server tree node format for rec-def
interface RecDefTreeNode {
	id: string;
	name: string;
	path: string;
	required?: boolean;
	repeatable?: boolean;
	hidden?: boolean;
	unmappable?: boolean;
	documentation?: RecDefDocumentation;
	children?: RecDefTreeNode[];
}

/**
 * Transform rec-def tree node to client TreeNode format
 */
function transformRecDefNode(node: RecDefTreeNode): TreeNode {
	const isAttribute = node.name.startsWith('@');

	// Extract useful fields from paragraphs for easier access
	const paragraphs = node.documentation?.paragraphs || [];
	const getParagraph = (name: string) => paragraphs.find(p => p.name === name)?.content;

	// Use Definition paragraph as primary description, fall back to description field
	const definition = getParagraph('Definition') || node.documentation?.description;

	return {
		id: node.id,
		name: node.name,
		path: node.path,
		isAttribute,
		hasValues: true, // rec-def elements can have values
		children: node.children?.map(transformRecDefNode),
		documentation: {
			description: definition,
			dataType: getParagraph('Value type') || getParagraph('Range'),
			required: node.required,
			repeatable: node.repeatable,
			vocabulary: getParagraph('Vocabulary'),
			examples: getParagraph('Example') ? [getParagraph('Example')!] : undefined,
			notes: getParagraph('Comment') || getParagraph('Note')
		}
	};
}

/**
 * Load rec-def (record definition) schema for target tree
 * Uses Narthex endpoint: GET /narthex/app/rec-def/:prefix
 */
export async function loadRecDef(prefix: string): Promise<{
	prefix: string;
	schemaVersions: string[];
	namespaces: { prefix: string; uri: string }[];
	functions: { name: string; code: string }[];
	tree: TreeNode[];
}> {
	const response = await fetch(`${API_BASE}/rec-def/${prefix}`);

	if (!response.ok) {
		if (response.status === 404) {
			throw new Error(`Record definition for prefix '${prefix}' not found`);
		}
		throw new Error(`Failed to load rec-def: ${response.statusText}`);
	}

	const data: RecDefResponse = await response.json();

	return {
		prefix: data.prefix,
		schemaVersions: data.schemaVersions,
		namespaces: data.namespaces,
		functions: data.functions,
		tree: data.tree.map(transformRecDefNode)
	};
}

// Server mapping response format from getMappingsJson endpoint
export interface ServerNodeMapping {
	inputPath: string;
	outputPath: string;
	operator?: string;
	groovyCode?: string;
	documentation?: string;
	siblings?: string[];
}

export interface ServerMappingsResponse {
	prefix: string;
	schemaVersion: string;
	locked: boolean;
	facts: Record<string, string>;
	functions: { name: string; code: string }[];
	mappings: ServerNodeMapping[];
}

// Client-side mapping format (matches mappingStore.Mapping interface)
export interface LoadedMapping {
	id: string;
	sourcePath: string;
	sourceId: string;
	sourceName: string;
	targetPath: string;
	targetId: string;
	targetName: string;
	label?: string;
	groovyCode?: string;
	operator?: string;
	documentation?: string;
	siblings?: string[];
	isOrphan?: boolean; // True if inputPath not found in source tree
}

export interface LoadedMappingsResult {
	prefix: string;
	schemaVersion: string;
	locked: boolean;
	facts: Record<string, string>;
	functions: { name: string; code: string }[];
	mappings: LoadedMapping[];
}

/**
 * Convert a path to an ID suitable for matching tree nodes
 * Must match the backend's ID generation for elements and attributes:
 * - Elements: path.replace("/", "_").replace(":", "_").replaceFirst("^_", "")
 * - Attributes: path.replace("/", "_").replace(":", "_").replace("@", "").replaceFirst("^_", "")
 * e.g., /rdf:RDF/edm:ProvidedCHO/dc:title -> rdf_RDF_edm_ProvidedCHO_dc_title
 * e.g., /rdf:RDF/edm:ProvidedCHO/@rdf:about -> rdf_RDF_edm_ProvidedCHO_rdf_about
 */
function pathToId(path: string): string {
	return path.replace(/\//g, '_').replace(/:/g, '_').replace(/@/g, '').replace(/^_/, '');
}

/**
 * Extract the last segment from a path as the name
 * e.g., /record/metadata/title -> title
 */
function pathToName(path: string): string {
	const segments = path.split('/');
	return segments[segments.length - 1] || path;
}

/**
 * Load existing mappings for a dataset as JSON
 * Uses Narthex endpoint: GET /narthex/app/dataset/:spec/mappings-json
 */
export async function loadMappings(spec: string): Promise<LoadedMappingsResult | null> {
	const response = await fetch(`${API_BASE}/dataset/${spec}/mappings-json`);

	if (!response.ok) {
		if (response.status === 404) {
			// No mappings found for this dataset
			return null;
		}
		throw new Error(`Failed to load mappings: ${response.statusText}`);
	}

	const data: ServerMappingsResponse = await response.json();

	// Transform server mappings to client format
	const mappings: LoadedMapping[] = data.mappings.map((m, index) => ({
		id: `loaded-${index}-${pathToId(m.inputPath)}-${pathToId(m.outputPath)}`,
		sourcePath: m.inputPath,
		sourceId: pathToId(m.inputPath),
		sourceName: pathToName(m.inputPath),
		targetPath: m.outputPath,
		targetId: pathToId(m.outputPath),
		targetName: pathToName(m.outputPath),
		groovyCode: m.groovyCode,
		operator: m.operator,
		documentation: m.documentation,
		siblings: m.siblings
	}));

	return {
		prefix: data.prefix,
		schemaVersion: data.schemaVersion,
		locked: data.locked,
		facts: data.facts,
		functions: data.functions,
		mappings
	};
}

// ============================================
// Preview Endpoints - Server-side Groovy Execution
// ============================================

/**
 * Sample record from the server
 */
export interface SamplePocket {
	index: number;
	id: string;
	xml: string;
}

/**
 * Response from preview-samples endpoint
 */
export interface PreviewSamplesResponse {
	spec: string;
	totalRecords: number;
	records: SamplePocket[];
}

/**
 * Response from preview-mapping endpoint
 */
export interface PreviewMappingResponse {
	success: boolean;
	spec: string;
	recordIndex?: number;
	recordId: string;
	inputXml: string;
	outputXml?: string;
	error?: string;
	errorType?: string;
	mappingPrefix: string;
}

/**
 * Load sample records for preview.
 * Returns raw source records as XML strings.
 *
 * @param spec Dataset specification
 * @param count Number of records to fetch (max 100 recommended)
 */
export async function loadPreviewSamples(spec: string, count: number = 20): Promise<PreviewSamplesResponse> {
	const response = await fetch(`${API_BASE}/dataset/${spec}/preview-samples/${count}`);

	if (!response.ok) {
		if (response.status === 404) {
			const error = await response.json();
			throw new Error(error.error || 'Source data not found');
		}
		throw new Error(`Failed to load preview samples: ${response.statusText}`);
	}

	return response.json();
}

/**
 * Execute mapping on a specific record by index.
 * Returns both input XML and transformed output.
 *
 * @param spec Dataset specification
 * @param recordIndex Zero-based index of the record
 */
export async function previewMappingByIndex(spec: string, recordIndex: number): Promise<PreviewMappingResponse> {
	const response = await fetch(`${API_BASE}/dataset/${spec}/preview-mapping/${recordIndex}`);

	if (!response.ok) {
		if (response.status === 404) {
			const error = await response.json();
			throw new Error(error.error || 'Record or mapping not found');
		}
		throw new Error(`Failed to preview mapping: ${response.statusText}`);
	}

	return response.json();
}

/**
 * Execute mapping on a specific record by ID.
 * Useful for jumping to a specific record in the dataset.
 *
 * @param spec Dataset specification
 * @param recordId The unique record ID
 */
export async function previewMappingById(spec: string, recordId: string): Promise<PreviewMappingResponse> {
	const encodedId = encodeURIComponent(recordId);
	const response = await fetch(`${API_BASE}/dataset/${spec}/preview-mapping-by-id/${encodedId}`);

	if (!response.ok) {
		if (response.status === 404) {
			const error = await response.json();
			throw new Error(error.error || 'Record not found');
		}
		throw new Error(`Failed to preview mapping: ${response.statusText}`);
	}

	return response.json();
}

// ============================================
// Record Search - Search through all records
// ============================================

/**
 * Search result for a matching record
 */
export interface RecordSearchResult {
	id: string;
	snippet: string;
	matchIndex: number;
}

/**
 * Response from search-records endpoint
 */
export interface RecordSearchResponse {
	spec: string;
	query: string;
	totalMatches: number;
	limit: number;
	results: RecordSearchResult[];
}

/**
 * Search through all records for content matching the query.
 * Returns a list of matching record IDs with snippets.
 *
 * @param spec Dataset specification
 * @param query Text to search for (case-insensitive)
 * @param limit Maximum number of results (default 50)
 */
export async function searchRecords(spec: string, query: string, limit: number = 50): Promise<RecordSearchResponse> {
	const params = new URLSearchParams({ query, limit: limit.toString() });
	const response = await fetch(`${API_BASE}/dataset/${spec}/search-records?${params}`);

	if (!response.ok) {
		if (response.status === 400) {
			const error = await response.json();
			throw new Error(error.error || 'Invalid search query');
		}
		if (response.status === 404) {
			const error = await response.json();
			throw new Error(error.error || 'Dataset not found');
		}
		throw new Error(`Failed to search records: ${response.statusText}`);
	}

	return response.json();
}

// ============================================
// Groovy Code Generation - Server-side code generation
// ============================================

/**
 * Response from generate-groovy endpoint
 */
export interface GenerateGroovyResponse {
	success: boolean;
	code: string;
	prefix: string;
	spec: string;
	error?: string;
	errorType?: string;
}

/**
 * Request payload for generate-groovy endpoint
 */
export interface GenerateGroovyRequest {
	mappings?: Array<{
		outputPath: string;
		groovyCode?: string;
	}>;
}

/**
 * Generate full Groovy mapping code from the server.
 * Uses sip-core's CodeGenerator to produce the complete mapping code.
 *
 * @param spec Dataset specification
 * @param customMappings Optional array of custom code overrides for specific output paths
 */
export async function generateFullGroovyCode(
	spec: string,
	customMappings?: Array<{ outputPath: string; groovyCode?: string }>
): Promise<GenerateGroovyResponse> {
	const body: GenerateGroovyRequest = {};
	if (customMappings && customMappings.length > 0) {
		body.mappings = customMappings;
	}

	const response = await fetch(`${API_BASE}/dataset/${spec}/generate-groovy`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(body)
	});

	if (!response.ok) {
		if (response.status === 404) {
			const error = await response.json();
			throw new Error(error.error || 'No mapping found for dataset');
		}
		throw new Error(`Failed to generate Groovy code: ${response.statusText}`);
	}

	return response.json();
}

/**
 * Response from generate-mapping-code endpoint
 */
export interface GenerateMappingCodeResponse {
	success: boolean;
	code: string;
	isGenerated: boolean;
	hasMapping: boolean;
	inputPath?: string | null;
	outputPath: string;
	error?: string;
	errorType?: string;
}

/**
 * Request payload for generate-mapping-code endpoint
 */
export interface GenerateMappingCodeRequest {
	mapping: {
		inputPath?: string;
		outputPath: string;
		groovyCode?: string;
	};
}

/**
 * Generate Groovy code for a single mapping.
 * Uses sip-core's CodeGenerator with EditPath to generate code for just one node mapping.
 *
 * @param spec Dataset specification
 * @param mapping The mapping to generate code for
 */
export async function generateMappingCode(
	spec: string,
	mapping: { inputPath?: string; outputPath: string; groovyCode?: string }
): Promise<GenerateMappingCodeResponse> {
	const body: GenerateMappingCodeRequest = { mapping };

	const response = await fetch(`${API_BASE}/dataset/${spec}/generate-mapping-code`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(body)
	});

	if (!response.ok) {
		if (response.status === 400) {
			const error = await response.json();
			throw new Error(error.error || 'Invalid request');
		}
		if (response.status === 404) {
			const error = await response.json();
			throw new Error(error.error || 'No mapping found for dataset');
		}
		throw new Error(`Failed to generate mapping code: ${response.statusText}`);
	}

	return response.json();
}

/**
 * Response from preview-mapping-code endpoint
 */
export interface PreviewMappingCodeResponse {
	success: boolean;
	spec: string;
	recordIndex: number;
	recordId: string;
	inputXml: string;
	outputXml?: string;
	mappingPrefix: string;
	inputPath?: string | null;
	outputPath?: string | null;
	error?: string;
	errorType?: string;
	// Variable bindings: maps variable names (e.g., "_title") to their values from the source record
	variableBindings?: Record<string, string>;
}

/**
 * Request payload for preview-mapping-code endpoint
 */
export interface PreviewMappingCodeRequest {
	mapping?: {
		inputPath?: string;
		outputPath: string;
		groovyCode?: string;
	};
	recordIndex: number;
}

/**
 * Preview mapping code execution.
 * Executes the provided Groovy code against a sample record and returns the result.
 *
 * @param spec Dataset specification
 * @param mapping The mapping to preview (with optional custom groovyCode)
 * @param recordIndex Zero-based index of the record to use
 */
export async function previewMappingCode(
	spec: string,
	mapping: { inputPath?: string; outputPath: string; groovyCode?: string } | undefined,
	recordIndex: number
): Promise<PreviewMappingCodeResponse> {
	const body: PreviewMappingCodeRequest = { recordIndex };
	if (mapping) {
		body.mapping = mapping;
	}

	const response = await fetch(`${API_BASE}/dataset/${spec}/preview-mapping-code`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(body)
	});

	if (!response.ok) {
		if (response.status === 404) {
			const error = await response.json();
			throw new Error(error.error || 'Dataset or mapping not found');
		}
		throw new Error(`Failed to preview mapping code: ${response.statusText}`);
	}

	return response.json();
}

// ============================================
// Save Mapping - Save mappings back to Narthex
// ============================================

/**
 * Response from save-mapping endpoint
 */
export interface SaveMappingResponse {
	success: boolean;
	version?: {
		hash: string;
		timestamp: string;
		source: string;
		description: string | null;
	};
	xml?: string;
	error?: string;
}

/**
 * Request payload for save-mapping endpoint
 */
export interface SaveMappingRequest {
	prefix: string;
	schemaVersion: string;
	locked?: boolean;
	facts: Record<string, string>;
	functions: Array<{ name: string; code: string }>;
	mappings: Array<{
		inputPath: string;
		outputPath: string;
		groovyCode?: string;
		operator?: string;
		documentation?: string;
		siblings?: string[];
	}>;
	description?: string;
}

/**
 * Save mappings to the server.
 * Converts the visual mappings to XML and saves to the dataset mapping repository.
 *
 * @param spec Dataset specification
 * @param data The mapping data to save
 */
export async function saveMapping(spec: string, data: SaveMappingRequest): Promise<SaveMappingResponse> {
	const response = await fetch(`${API_BASE}/dataset/${spec}/save-mapping`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(data)
	});

	if (!response.ok) {
		if (response.status === 400) {
			const error = await response.json();
			throw new Error(error.error || 'Invalid mapping data');
		}
		if (response.status === 404) {
			const error = await response.json();
			throw new Error(error.error || 'Dataset not found');
		}
		throw new Error(`Failed to save mapping: ${response.statusText}`);
	}

	return response.json();
}
