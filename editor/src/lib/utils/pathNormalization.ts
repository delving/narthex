/**
 * Path normalization utilities for mapping paths.
 *
 * Mapping files use a custom prefix on the root element (e.g., /edm:RDF),
 * but rec-def uses unprefixed root (e.g., /RDF).
 */

/**
 * Normalize a mapping path to match rec-def format.
 * Only strips the prefix from the FIRST element (root).
 * - /edm:RDF/edm:ProvidedCHO/dc:title -> /RDF/edm:ProvidedCHO/dc:title
 * - /abc:Root/foo/bar -> /Root/foo/bar
 * - /RDF/ore:Aggregation -> /RDF/ore:Aggregation (already normalized)
 */
export function normalizeMappingPath(path: string): string {
	// Strip namespace prefix from root element only: /prefix:Element -> /Element
	// The regex matches: leading slash, then prefix with colon, at the start only
	return path.replace(/^\/[a-zA-Z0-9_-]+:/, '/');
}

/**
 * Strip qualifier from a path segment for matching.
 * Mapping files use qualifiers like edm:Agent[person], edm:Agent[creator]
 * to create multiple instances of the same element type.
 * - /edm:RDF/edm:Agent[person]/skos:prefLabel -> /edm:RDF/edm:Agent/skos:prefLabel
 */
export function stripQualifiers(path: string): string {
	return path.replace(/\[[^\]]+\]/g, '');
}

/**
 * Extract qualifier from a path if present.
 * Returns { qualifier, qualifiedSegment, segmentIndex } or null if no qualifier.
 * - /edm:RDF/edm:Agent[person]/skos:prefLabel -> { qualifier: "person", qualifiedSegment: "edm:Agent[person]", segmentIndex: 2 }
 */
export function extractQualifier(path: string): { qualifier: string; qualifiedSegment: string; segmentIndex: number } | null {
	const match = path.match(/([^\/]+)\[([^\]]+)\]/);
	if (!match) return null;
	const qualifiedSegment = match[0];
	const qualifier = match[2];
	// Find the index of the qualified segment in the path
	const segments = path.split('/').filter(Boolean);
	const segmentIndex = segments.findIndex(s => s === qualifiedSegment);
	return { qualifier, qualifiedSegment, segmentIndex: segmentIndex + 1 }; // +1 because split removes leading empty string
}

/**
 * Get the base element path (without qualifier) from a qualified path.
 * - /edm:RDF/edm:Agent[person]/skos:prefLabel -> /edm:RDF/edm:Agent
 */
export function getBaseElementPath(qualifiedPath: string): string {
	const qualifierInfo = extractQualifier(qualifiedPath);
	if (!qualifierInfo) return qualifiedPath;

	const segments = qualifiedPath.split('/');
	// Replace qualified segment with unqualified version
	const result = segments.map((seg) => {
		if (seg === qualifierInfo.qualifiedSegment) {
			return seg.replace(/\[[^\]]+\]/, '');
		}
		return seg;
	});
	// Return path up to and including the (now unqualified) segment
	const targetIndex = result.findIndex(s => s === qualifierInfo.qualifiedSegment.replace(/\[[^\]]+\]/, ''));
	return result.slice(0, targetIndex + 1).join('/');
}

/**
 * Get the element path portion of a qualified attribute path.
 * - /edm:RDF/edm:Agent[person]/skos:prefLabel -> /edm:RDF/edm:Agent[person]
 */
export function getQualifiedElementPath(path: string): string | null {
	const qualifierInfo = extractQualifier(path);
	if (!qualifierInfo) return null;

	// Return the path up to and including the qualified segment
	const segments = path.split('/');
	const qualifiedIndex = segments.findIndex(s => s === qualifierInfo.qualifiedSegment);
	if (qualifiedIndex === -1) return null;
	return segments.slice(0, qualifiedIndex + 1).join('/');
}

/**
 * Try to find a target node using various normalization strategies.
 * Returns the node if found, undefined otherwise.
 *
 * Strategies (in order):
 * 1. Exact match
 * 2. Normalized (strip root prefix)
 * 3. With qualifiers stripped
 * 4. Both normalized and qualifier-stripped
 */
export function findTargetPath(targetPaths: Set<string>, mappingPath: string): string | undefined {
	// Try exact match first
	if (targetPaths.has(mappingPath)) return mappingPath;

	// Try normalized (strip root prefix)
	const normalizedPath = normalizeMappingPath(mappingPath);
	if (targetPaths.has(normalizedPath)) return normalizedPath;

	// Try with qualifiers stripped
	const strippedPath = stripQualifiers(mappingPath);
	if (strippedPath !== mappingPath && targetPaths.has(strippedPath)) return strippedPath;

	// Try both normalized and qualifier-stripped
	const normalizedStripped = stripQualifiers(normalizedPath);
	if (normalizedStripped !== normalizedPath && targetPaths.has(normalizedStripped)) return normalizedStripped;

	return undefined;
}
