// Mapping link with optional label for disambiguation
export interface MappingLink {
	field: string;         // Field name (source or target)
	label?: string;        // Optional label for disambiguation (e.g., "nl", "en", "primary")
}

// Quality statistics for source fields (from source-index analysis)
export interface FieldQuality {
	totalRecords: number;      // Total records in dataset
	recordsWithValue: number;  // Records that have this field
	emptyCount: number;        // Records with empty value
	completeness: number;      // Percentage of records with value (0-100)
	avgPerRecord: number;      // Average occurrences per record
	emptyRate: number;         // Percentage of empty values (0-100)
}

// Length distribution for source fields
export interface LengthDistribution {
	range: string;   // e.g., "0-10", "11-50", "51-100"
	count: number;   // Number of values in this range
}

// Documentation for target fields from RecDef
export interface FieldDocumentation {
	description?: string;      // Human-readable description
	dataType?: string;         // Expected data type (string, date, uri, etc.)
	required?: boolean;        // Whether field is required
	repeatable?: boolean;      // Whether field can repeat
	vocabulary?: string;       // Controlled vocabulary URI if applicable
	examples?: string[];       // Example values
	notes?: string;            // Additional implementation notes
}

// Tree node representing XML structure from source analysis
export interface TreeNode {
	id: string;
	name: string;
	path: string;
	count?: number;        // Number of occurrences in dataset
	children?: TreeNode[];
	isAttribute?: boolean; // XML attribute vs element
	hasValues?: boolean;   // Has text content
	isMapped?: boolean;    // Connected to target
	mappedTo?: MappingLink[];   // Target fields this maps to (can be multiple)
	mappedFrom?: MappingLink[]; // Source fields that map to this (can be multiple)
	documentation?: FieldDocumentation; // RecDef documentation for target fields
	quality?: FieldQuality;             // Quality statistics from source analysis
	lengths?: LengthDistribution[];     // Value length distribution
	isOrphan?: boolean;    // True if this node represents an orphaned mapping path (no longer in source data)
	isConstant?: boolean;  // True if this is the special constant node for direct text input
	isFacts?: boolean;     // True if this is the facts container node
	isFact?: boolean;      // True if this is an individual fact entry (draggable)
	factValue?: string;    // The value of a fact (for isFact nodes)
	mappingPath?: string;  // Original mapping path (for debugging orphans)
	qualifier?: string;    // Qualifier label for qualified variants (e.g., "person" in edm:Agent[person])
	isQualifiedVariant?: boolean; // True if this is a qualified variant of a base element
	basePath?: string;     // The base rec-def path for qualified variants (e.g., /RDF/edm:Agent for /RDF/edm:Agent[person])
}

// Connection between source and target nodes
export interface Connection {
	id: string;
	sourceId: string;
	targetId: string;
	sourcePath: string;
	targetPath: string;
}

// Mapping state
export interface MappingState {
	datasetSpec: string;
	mappingPrefix: string;
	sourceTree: TreeNode[];
	targetTree: TreeNode[];
	connections: Connection[];
	groovyCode: string;
	selectedSourceNode: string | null;
	selectedTargetNode: string | null;
}

// Sample record for preview - represents source XML as nested object
export interface SampleRecord {
	[key: string]: string | string[] | SampleRecord | SampleRecord[] | undefined;
	_attr?: { [key: string]: string }; // XML attributes
}

// Transformed output record
export interface OutputRecord {
	'@context'?: string;
	'@type'?: string;
	[key: string]: string | object | undefined;
}

// ============================================
// Field Status Types (from status.json per field)
// ============================================

// Detected data types
export type FieldDataType = 'TEXT' | 'INTEGER' | 'DECIMAL' | 'DATE' | 'URL' | 'EMAIL' | 'IDENTIFIER' | 'BOOLEAN';

// Type detection analysis
export interface TypeInfo {
	dominantType: FieldDataType;
	consistency: number;           // Percentage of values matching dominant type (0-100)
	isMixed: boolean;              // True if consistency < 95%
	distribution: Record<string, number>; // Type -> count
}

// Pattern types detected in values
export type PatternType = 'UUID' | 'URN' | 'PREFIX_ID' | 'HTTP_URI' | 'HTTPS_URI' | 'FTP_URI' | 'OTHER_URI' | 'CUSTOM' | 'NONE';

// URI validation results
export interface UriValidation {
	total: number;
	valid: number;
	invalid: number;
	validRate: number;             // Percentage (0-100)
	samples: string[];             // Up to 10 invalid URI examples
}

// Pattern analysis
export interface PatternInfo {
	dominantPattern: PatternType;
	patternConsistency: number;    // Percentage (0-100)
	patterns: Record<string, number>; // Pattern -> count
	uriValidation?: UriValidation;
}

// Min/max/avg statistics
export interface RangeStats {
	min: number;
	max: number;
	avg: number;
}

// Issue group with counts and samples
export interface IssueGroup {
	total: number;
	samples: string[];             // Up to 10 example values with issues
}

// Whitespace issues breakdown
export interface WhitespaceIssues extends IssueGroup {
	leadingCount: number;
	trailingCount: number;
	multipleSpacesCount: number;
}

// Encoding issues breakdown
export interface EncodingIssues extends IssueGroup {
	mojibake: number;
	htmlEntities: number;
	escapedChars: number;
	controlChars: number;
	replacementChars: number;
}

// Date/numeric outliers breakdown
export interface OutlierIssues extends IssueGroup {
	futureDates: number;
	ancientDates: number;
	suspiciousYears: number;
}

// Value statistics (length, word count, issues)
export interface ValueStats {
	length: RangeStats;
	wordCount: RangeStats;
	whitespace?: WhitespaceIssues;
	encodingIssues?: EncodingIssues;
	outliers?: OutlierIssues;
	numericRange?: {
		min: number;
		max: number;
		count: number;
	};
}

// Complete field status from status.json
export interface FieldStatus {
	uniqueCount: number;
	typeInfo?: TypeInfo;
	patternInfo?: PatternInfo;
	valueStats?: ValueStats;
}

// ============================================
// Dataset Quality Summary (from /quality-summary endpoint)
// ============================================

// Problematic field info
export interface ProblematicField {
	path: string;
	tag: string;
	completeness: number;
	emptyRate: number;
	recordsWithValue: number;
	totalRecords: number;
	avgPerRecord: number;
	totalCount: number;
	uniqueCount: number;
	uniqueness: number;
	issues: string[];
	issueCount: number;
	qualityScore: number;
	scoreCategory: 'excellent' | 'good' | 'fair' | 'poor';
}

// Field present in every record
export interface UniversalField {
	path: string;
	tag: string;
	avgPerRecord: number;
}

// Identifier field
export interface IdentifierField {
	path: string;
	tag: string;
	uniqueCount: number;
	totalCount: number;
}

// Quality score distribution
export interface ScoreDistribution {
	excellent: number;  // >= 90
	good: number;       // 70-89
	fair: number;       // 50-69
	poor: number;       // < 50
}

// Completeness distribution (mutually exclusive ranges)
export interface CompletenessDistribution {
	complete: number;   // 100% completeness
	high: number;       // 80-99% completeness
	medium: number;     // 50-79% completeness
	low: number;        // <50% completeness
}

// Uniqueness distribution
export interface UniquenessDistribution {
	identifier: number; // 100% (likely identifier)
	high: number;       // > 80%
	medium: number;     // 20-80%
	low: number;        // < 20%
}

// Complete dataset quality summary
export interface QualitySummary {
	totalFields: number;
	leafFields: number;
	totalRecords: number;
	fieldsWithIssues: number;
	fieldsInEveryRecord: number;
	fieldsInEveryRecordList: UniversalField[];
	identifierFieldsList: IdentifierField[];
	avgFieldsPerRecord: number;
	avgUniqueFieldsPerRecord: number;
	avgUniqueness: number;
	overallScore: number;
	completenessDistribution: CompletenessDistribution;
	uniquenessDistribution: UniquenessDistribution;
	scoreDistribution: ScoreDistribution;
	issuesByType: Record<string, number>;
	problematicFields: ProblematicField[];
}
