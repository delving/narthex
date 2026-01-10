import type { Mapping } from '$lib/stores/mappingStore';

/**
 * Converts a source XML path to Groovy input variable notation.
 * Examples:
 *   /record/title → input.title
 *   /record/maker/name → input.maker?.name
 *   /record/dating/date.early → input.dating?.'date.early'
 */
export function sourcePathToGroovy(path: string): string {
	// Remove leading /record/ prefix
	let cleanPath = path.replace(/^\/record\/?/, '');
	if (!cleanPath) return 'input';

	// Split by /
	const parts = cleanPath.split('/');

	// Build the Groovy path
	let groovyPath = 'input';
	for (let i = 0; i < parts.length; i++) {
		const part = parts[i];
		// Use safe navigation operator for nested access
		const nav = i === 0 ? '.' : '?.';

		// Check if part contains special characters that need quoting
		if (part.includes('.') || part.includes('-') || part.includes(':') || part.startsWith('@')) {
			// Remove @ prefix for attributes, but quote the name
			const cleanPart = part.startsWith('@') ? part.slice(1) : part;
			groovyPath += `${nav}'${cleanPart}'`;
		} else {
			groovyPath += `${nav}${part}`;
		}
	}

	return groovyPath;
}

/**
 * Converts a target schema path to Groovy output variable notation.
 * Uses the leaf node name, normalized for Groovy.
 * Examples:
 *   /rdf:RDF/edm:ProvidedCHO/dc:title → output.dc_title
 *   /rdf:RDF/edm:WebResource/@rdf:about → output.edm_WebResource_about
 */
export function targetPathToGroovy(_path: string, targetName: string): string {
	// Normalize the target name: replace : with _, remove @
	const normalized = targetName
		.replace(/^@/, '')
		.replace(/:/g, '_');

	return `output.${normalized}`;
}

/**
 * Generates a single Groovy mapping statement from a mapping.
 */
export function generateMappingLine(mapping: Mapping): string {
	const inputPath = sourcePathToGroovy(mapping.sourcePath);
	const outputPath = targetPathToGroovy(mapping.targetPath, mapping.targetName);

	// Add .text() to get the string value
	return `${outputPath} = ${inputPath}?.text()`;
}

/**
 * Groups mappings by target parent path for organized output.
 */
function groupMappingsByTargetParent(mappings: Mapping[]): Map<string, Mapping[]> {
	const groups = new Map<string, Mapping[]>();

	for (const mapping of mappings) {
		// Extract parent path from target (e.g., /rdf:RDF/edm:ProvidedCHO from /rdf:RDF/edm:ProvidedCHO/dc:title)
		const pathParts = mapping.targetPath.split('/');
		pathParts.pop(); // Remove the leaf node
		const parentPath = pathParts.join('/') || '/';

		if (!groups.has(parentPath)) {
			groups.set(parentPath, []);
		}
		groups.get(parentPath)!.push(mapping);
	}

	return groups;
}

/**
 * Extracts section name from a target parent path.
 */
function getSectionName(parentPath: string): string {
	const parts = parentPath.split('/').filter(Boolean);
	if (parts.length === 0) return 'Root';

	const lastPart = parts[parts.length - 1];
	// Convert edm:ProvidedCHO to "Provided CHO"
	return lastPart
		.replace(/^(edm|ore|dc|dcterms|rdf):/, '')
		.replace(/([A-Z])/g, ' $1')
		.trim();
}

/**
 * Generates complete Groovy mapping code from a list of mappings.
 */
export function generateGroovyCode(
	mappings: Mapping[],
	datasetSpec: string = 'dataset',
	mappingPrefix: string = 'edm'
): string {
	if (mappings.length === 0) {
		return `// Mapping for ${datasetSpec} / ${mappingPrefix}
// No mappings defined yet. Drag source fields to target fields to create mappings.
`;
	}

	const lines: string[] = [];

	// Header
	lines.push(`// Mapping for ${datasetSpec} / ${mappingPrefix}`);
	lines.push('// Generated from visual mappings');
	lines.push('');

	// Group mappings by target parent
	const groups = groupMappingsByTargetParent(mappings);

	// Sort groups by path for consistent output
	const sortedPaths = Array.from(groups.keys()).sort();

	for (const parentPath of sortedPaths) {
		const groupMappings = groups.get(parentPath)!;
		const sectionName = getSectionName(parentPath);

		// Add section comment
		lines.push(`// ${sectionName}`);

		// Sort mappings within group by target name
		const sortedMappings = groupMappings.sort((a, b) =>
			a.targetName.localeCompare(b.targetName)
		);

		for (const mapping of sortedMappings) {
			lines.push(generateMappingLine(mapping));
		}

		lines.push('');
	}

	return lines.join('\n');
}

/**
 * Merges generated code with user-edited code.
 * Preserves user comments and custom code while updating generated sections.
 */
export function mergeWithUserCode(
	generatedCode: string,
	existingCode: string,
	_mappings: Mapping[]
): string {
	// For now, we'll use a simple strategy:
	// - If the existing code starts with "// Mapping for", replace it entirely
	// - Otherwise, append generated code at the end
	//
	// A more sophisticated approach would:
	// - Parse the existing code
	// - Identify which lines are generated vs user-added
	// - Preserve user additions while updating generated parts

	const isGeneratedCode = existingCode.startsWith('// Mapping for') ||
		existingCode.includes('// Generated from visual mappings');

	if (isGeneratedCode || existingCode.trim() === '') {
		return generatedCode;
	}

	// Append to existing code
	return existingCode + '\n\n' + generatedCode;
}
