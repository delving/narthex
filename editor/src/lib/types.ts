// Mapping link with optional label for disambiguation
export interface MappingLink {
	field: string;         // Field name (source or target)
	label?: string;        // Optional label for disambiguation (e.g., "nl", "en", "primary")
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
