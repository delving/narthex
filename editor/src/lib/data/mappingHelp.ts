// Mapping help content - structured for display in the UI

export interface HelpSection {
	id: string;
	title: string;
	content: string; // Markdown content
	tags?: string[]; // For searching
}

export interface QuickTip {
	fieldType: string; // 'text', 'link', 'date', 'attribute', etc.
	tips: string[];
}

// Quick tips shown based on field type
export const quickTips: QuickTip[] = [
	{
		fieldType: 'text',
		tips: [
			'Use `.sanitize()` to clean whitespace and line breaks',
			'Use `.text()` to get the text content of a node',
			'Use `[0]` to get the first value from a list'
		]
	},
	{
		fieldType: 'link',
		tips: [
			'Use `.sanitizeURI()` to encode special characters',
			'Build URIs with string interpolation: `"${baseUrl}/resource/${id}"`',
			'Validate URLs before mapping to avoid broken links'
		]
	},
	{
		fieldType: 'date',
		tips: [
			'Use `calculateAge()` for date calculations',
			'Common format: YYYY-MM-DD',
			'Handle partial dates (year only, year-month)'
		]
	},
	{
		fieldType: 'attribute',
		tips: [
			'Access attributes with `@` prefix: `node["@lang"]`',
			'Attributes are returned as lists, use `[0]` for single value',
			'Check existence before accessing: `if (node["@id"])`'
		]
	},
	{
		fieldType: 'multiple',
		tips: [
			'Use `*` operator to apply function to each value',
			'Use `**` to apply only to first value',
			'Use `>>` to process all values at once',
			'Join values with: `list >> { it.join(", ") }`'
		]
	}
];

// Common patterns - quick reference
export const commonPatterns = [
	{
		name: 'Simple text mapping',
		code: 'input.record.title.text().sanitize()',
		description: 'Get text content and clean whitespace'
	},
	{
		name: 'First value only',
		code: 'input.record.creator[0].text()',
		description: 'Get first value when field may repeat'
	},
	{
		name: 'Fallback value',
		code: 'input.record.title ?: "Untitled"',
		description: 'Provide default if field is empty'
	},
	{
		name: 'Build URI',
		code: '"http://example.org/resource/" + id.sanitizeURI()',
		description: 'Create URI with encoded identifier'
	},
	{
		name: 'Process multiple values',
		code: 'input.record.subject * { it.sanitize() }',
		description: 'Apply transformation to each value'
	},
	{
		name: 'Conditional mapping',
		code: 'if (input.record.type) { input.record.type.text() }',
		description: 'Only map if field exists'
	},
	{
		name: 'Combine fields',
		code: '(input.record.creator + input.record.contributor) * { it.text() }',
		description: 'Merge multiple source fields'
	},
	{
		name: 'Split and process',
		code: 'input.record.keywords.text().split(";") * { it.trim() }',
		description: 'Split delimited values and clean each'
	}
];

// Full help sections (parsed from markdown)
export const helpSections: HelpSection[] = [
	{
		id: 'node-navigation',
		title: 'Node Navigation',
		tags: ['input', 'node', 'path', 'attribute', 'children'],
		content: `
### Accessing Input Data

When working with XML nodes, you use \`GroovyNode\` objects with flexible navigation:

**Attribute lookup** - Use \`@\` prefix:
\`\`\`groovy
input.record["@id"]  // Get the "id" attribute
\`\`\`

**Get all children** - Use \`*\`:
\`\`\`groovy
input.record["*"]  // All child elements
\`\`\`

**First matching element** - Use \`_\` suffix:
\`\`\`groovy
input.record["title_"]  // First "title" with text
\`\`\`

**All matching elements** - Just use the name:
\`\`\`groovy
input.record.title  // All "title" elements
\`\`\`
`
	},
	{
		id: 'string-functions',
		title: 'String Functions',
		tags: ['string', 'text', 'sanitize', 'replace', 'split'],
		content: `
### Text Processing

**Basic functions:**
\`\`\`groovy
.text()        // Get text content
.sanitize()    // Clean whitespace/newlines
.sanitizeURI() // Encode for URLs
.trim()        // Remove leading/trailing space
\`\`\`

**Transformation:**
\`\`\`groovy
.toLowerCase()
.toUpperCase()
.replaceAll(pattern, replacement)
.split(delimiter)
\`\`\`

**Checking content:**
\`\`\`groovy
.contains(substring)
.startsWith(prefix)
.endsWith(suffix)
.matches(regex)
\`\`\`
`
	},
	{
		id: 'list-operators',
		title: 'List Operators',
		tags: ['list', 'multiple', 'each', 'all', 'operator'],
		content: `
### Working with Multiple Values

**\`*\` - Apply to each element:**
\`\`\`groovy
input.record.subject * { it.sanitize() }
\`\`\`

**\`**\` - Apply to first element only:**
\`\`\`groovy
input.record.title ** { it.toUpperCase() }
\`\`\`

**\`>>\` - Apply once to entire list:**
\`\`\`groovy
input.record.author >> { all -> all.join("; ") }
\`\`\`

**\`+\` - Concatenate lists:**
\`\`\`groovy
input.record.creator + input.record.contributor
\`\`\`

**\`|\` - Create tuples (pair elements):**
\`\`\`groovy
keys | values  // Creates list of pairs
\`\`\`
`
	},
	{
		id: 'conditionals',
		title: 'Conditional Mapping',
		tags: ['if', 'condition', 'discard', 'skip', 'elvis'],
		content: `
### Conditional Logic

**Elvis operator** - Default value:
\`\`\`groovy
input.record.title ?: "Untitled"
\`\`\`

**Safe navigation** - Avoid nulls:
\`\`\`groovy
input.record?.title?.text()
\`\`\`

**Conditional mapping:**
\`\`\`groovy
if (input.record.description) {
    input.record.description.text()
}
\`\`\`

**Discarding records:**
\`\`\`groovy
discard("Reason to skip this record")
discardIf(condition, "Skip if true")
discardIfNot(condition, "Skip if false")
\`\`\`
`
	},
	{
		id: 'date-functions',
		title: 'Date Functions',
		tags: ['date', 'age', 'birth', 'death', 'calculate'],
		content: `
### Date Processing

**Calculate age:**
\`\`\`groovy
calculateAge(birthDate, deathDate)
// Returns age in years as String

calculateAge(birthDate, deathDate, true)
// Auto-reorder if dates are swapped
\`\`\`

**Calculate age range:**
\`\`\`groovy
calculateAgeRange(birthDate, deathDate)
// Returns range like "20 – 29"
\`\`\`

**Common date patterns:**
- \`YYYY\` - Year only
- \`YYYY-MM\` - Year and month
- \`YYYY-MM-DD\` - Full date
`
	},
	{
		id: 'variables',
		title: 'Available Variables',
		tags: ['variable', 'input', 'facts', 'identifier', 'lookup'],
		content: `
### Mapping Variables

**Input record:**
\`\`\`groovy
_input  // Source record root node
\`\`\`

**Identifier:**
\`\`\`groovy
_uniqueIdentifier  // Current record's unique ID
\`\`\`

**Mapping facts:**
\`\`\`groovy
_facts           // All facts as map
provider         // Fact variables are available directly
baseUrl
\`\`\`

**Option lookups:**
\`\`\`groovy
_optLookup       // Vocabulary lookups
_optLookup.type[value]  // Look up value
\`\`\`
`
	},
	{
		id: 'uri-building',
		title: 'Building URIs',
		tags: ['uri', 'url', 'link', 'resource', 'encode'],
		content: `
### Creating URIs

**Basic pattern:**
\`\`\`groovy
"http://example.org/resource/" + id.sanitizeURI()
\`\`\`

**With interpolation:**
\`\`\`groovy
"\${baseUrl}/object/\${_uniqueIdentifier}"
\`\`\`

**sanitizeURI() encodes:**
- Space → \`%20\`
- \`[\` → \`%5B\`
- \`]\` → \`%5D\`
- \`\\\` → \`%5C\`

**Combining parts:**
\`\`\`groovy
[baseUrl, "collection", id].join("/")
\`\`\`
`
	},
	{
		id: 'best-practices',
		title: 'Best Practices',
		tags: ['tips', 'best', 'practice', 'advice'],
		content: `
### Tips for Good Mappings

1. **Check field existence** before accessing
   \`\`\`groovy
   if (input.record.title) { ... }
   \`\`\`

2. **Use sanitize()** on text fields
   \`\`\`groovy
   title.text().sanitize()
   \`\`\`

3. **Use sanitizeURI()** for link fields
   \`\`\`groovy
   "http://..." + id.sanitizeURI()
   \`\`\`

4. **Node access returns lists** - use \`[0]\` for single values
   \`\`\`groovy
   input.record.title[0].text()
   \`\`\`

5. **Test with edge cases** - empty fields, special characters

6. **Use meaningful discard reasons**
   \`\`\`groovy
   discardIf(!title, "Missing required title")
   \`\`\`
`
	}
];

// Get tips for a specific field type
export function getTipsForFieldType(fieldType: string): string[] {
	const tip = quickTips.find(t => t.fieldType === fieldType);
	return tip?.tips ?? quickTips.find(t => t.fieldType === 'text')?.tips ?? [];
}

// Search help sections
export function searchHelp(query: string): HelpSection[] {
	if (!query.trim()) return helpSections;

	const lowerQuery = query.toLowerCase();
	return helpSections.filter(section =>
		section.title.toLowerCase().includes(lowerQuery) ||
		section.content.toLowerCase().includes(lowerQuery) ||
		section.tags?.some(tag => tag.toLowerCase().includes(lowerQuery))
	);
}
