import { describe, it, expect } from 'vitest';
import {
	normalizeMappingPath,
	stripQualifiers,
	extractQualifier,
	getBaseElementPath,
	getQualifiedElementPath,
	findTargetPath
} from './pathNormalization';

describe('normalizeMappingPath', () => {
	it('strips prefix from EDM root element', () => {
		expect(normalizeMappingPath('/edm:RDF/ore:Aggregation')).toBe('/RDF/ore:Aggregation');
		expect(normalizeMappingPath('/edm:RDF/edm:ProvidedCHO/dc:title')).toBe('/RDF/edm:ProvidedCHO/dc:title');
	});

	it('strips prefix from any custom root element', () => {
		expect(normalizeMappingPath('/abc:Root/foo/bar')).toBe('/Root/foo/bar');
		expect(normalizeMappingPath('/xyz:Document/section/para')).toBe('/Document/section/para');
		expect(normalizeMappingPath('/lido:Record/lido:descriptiveMetadata')).toBe('/Record/lido:descriptiveMetadata');
	});

	it('leaves already normalized paths unchanged', () => {
		expect(normalizeMappingPath('/RDF/ore:Aggregation')).toBe('/RDF/ore:Aggregation');
		expect(normalizeMappingPath('/Root/foo/bar')).toBe('/Root/foo/bar');
	});

	it('only strips prefix from root element, not others', () => {
		// The edm: prefix on ProvidedCHO should remain
		expect(normalizeMappingPath('/edm:RDF/edm:ProvidedCHO')).toBe('/RDF/edm:ProvidedCHO');
		// Multiple prefixes after root should remain
		expect(normalizeMappingPath('/edm:RDF/ore:Aggregation/edm:dataProvider')).toBe('/RDF/ore:Aggregation/edm:dataProvider');
	});

	it('handles attribute paths', () => {
		expect(normalizeMappingPath('/edm:RDF/ore:Aggregation/@rdf:about')).toBe('/RDF/ore:Aggregation/@rdf:about');
		expect(normalizeMappingPath('/edm:RDF/edm:Agent/@rdf:about')).toBe('/RDF/edm:Agent/@rdf:about');
	});

	it('handles paths with qualifiers', () => {
		expect(normalizeMappingPath('/edm:RDF/edm:Agent[person]')).toBe('/RDF/edm:Agent[person]');
		expect(normalizeMappingPath('/edm:RDF/edm:Agent[person]/skos:prefLabel')).toBe('/RDF/edm:Agent[person]/skos:prefLabel');
	});

	it('handles root-only paths', () => {
		expect(normalizeMappingPath('/edm:RDF')).toBe('/RDF');
		expect(normalizeMappingPath('/abc:Root')).toBe('/Root');
	});

	it('handles prefixes with numbers and hyphens', () => {
		expect(normalizeMappingPath('/edm-v2:RDF/data')).toBe('/RDF/data');
		expect(normalizeMappingPath('/ns1:Root/element')).toBe('/Root/element');
	});
});

describe('stripQualifiers', () => {
	it('removes qualifiers from paths', () => {
		expect(stripQualifiers('/edm:RDF/edm:Agent[person]')).toBe('/edm:RDF/edm:Agent');
		expect(stripQualifiers('/edm:RDF/edm:Agent[person]/skos:prefLabel')).toBe('/edm:RDF/edm:Agent/skos:prefLabel');
	});

	it('removes multiple qualifiers', () => {
		expect(stripQualifiers('/root/elem[a]/sub[b]')).toBe('/root/elem/sub');
	});

	it('leaves paths without qualifiers unchanged', () => {
		expect(stripQualifiers('/edm:RDF/ore:Aggregation')).toBe('/edm:RDF/ore:Aggregation');
	});
});

describe('extractQualifier', () => {
	it('extracts qualifier from path', () => {
		const result = extractQualifier('/edm:RDF/edm:Agent[person]/skos:prefLabel');
		expect(result).not.toBeNull();
		expect(result!.qualifier).toBe('person');
		expect(result!.qualifiedSegment).toBe('edm:Agent[person]');
	});

	it('returns null for paths without qualifiers', () => {
		expect(extractQualifier('/edm:RDF/ore:Aggregation')).toBeNull();
	});

	it('handles various qualifier names', () => {
		expect(extractQualifier('/root/elem[creator]')?.qualifier).toBe('creator');
		expect(extractQualifier('/root/elem[data-provider]')?.qualifier).toBe('data-provider');
	});
});

describe('getBaseElementPath', () => {
	it('returns path up to unqualified element', () => {
		expect(getBaseElementPath('/edm:RDF/edm:Agent[person]/skos:prefLabel')).toBe('/edm:RDF/edm:Agent');
	});

	it('returns path unchanged if no qualifier', () => {
		expect(getBaseElementPath('/edm:RDF/ore:Aggregation')).toBe('/edm:RDF/ore:Aggregation');
	});
});

describe('getQualifiedElementPath', () => {
	it('returns path up to qualified element', () => {
		expect(getQualifiedElementPath('/edm:RDF/edm:Agent[person]/skos:prefLabel')).toBe('/edm:RDF/edm:Agent[person]');
	});

	it('returns null for paths without qualifiers', () => {
		expect(getQualifiedElementPath('/edm:RDF/ore:Aggregation')).toBeNull();
	});
});

describe('findTargetPath', () => {
	const recDefPaths = new Set([
		'/RDF/ore:Aggregation',
		'/RDF/ore:Aggregation/@rdf:about',
		'/RDF/edm:ProvidedCHO',
		'/RDF/edm:ProvidedCHO/dc:title',
		'/RDF/edm:Agent',
		'/RDF/edm:Agent/@rdf:about',
		'/RDF/edm:Agent/skos:prefLabel',
	]);

	it('finds exact matches', () => {
		expect(findTargetPath(recDefPaths, '/RDF/ore:Aggregation')).toBe('/RDF/ore:Aggregation');
	});

	it('finds normalized paths (strips root prefix)', () => {
		expect(findTargetPath(recDefPaths, '/edm:RDF/ore:Aggregation')).toBe('/RDF/ore:Aggregation');
		expect(findTargetPath(recDefPaths, '/edm:RDF/edm:ProvidedCHO/dc:title')).toBe('/RDF/edm:ProvidedCHO/dc:title');
	});

	it('finds paths with qualifiers stripped', () => {
		expect(findTargetPath(recDefPaths, '/RDF/edm:Agent[person]')).toBe('/RDF/edm:Agent');
	});

	it('finds paths with both normalization and qualifier stripping', () => {
		expect(findTargetPath(recDefPaths, '/edm:RDF/edm:Agent[person]')).toBe('/RDF/edm:Agent');
		expect(findTargetPath(recDefPaths, '/edm:RDF/edm:Agent[person]/skos:prefLabel')).toBe('/RDF/edm:Agent/skos:prefLabel');
	});

	it('returns undefined for non-existent paths', () => {
		expect(findTargetPath(recDefPaths, '/edm:RDF/nonexistent')).toBeUndefined();
	});

	it('handles attribute paths', () => {
		expect(findTargetPath(recDefPaths, '/edm:RDF/ore:Aggregation/@rdf:about')).toBe('/RDF/ore:Aggregation/@rdf:about');
		expect(findTargetPath(recDefPaths, '/edm:RDF/edm:Agent/@rdf:about')).toBe('/RDF/edm:Agent/@rdf:about');
	});
});
