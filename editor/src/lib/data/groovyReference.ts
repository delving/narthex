// Groovy functions and variables available in mapping context

export interface FunctionDoc {
	name: string;
	signature: string;
	description: string;
	example?: string;
	insertText: string; // What to insert when clicked
}

export interface VariableDoc {
	name: string;
	type: string;
	description: string;
	insertText: string;
}

export interface FunctionCategory {
	name: string;
	description: string;
	functions: FunctionDoc[];
}

// Default variables always available
export const defaultVariables: VariableDoc[] = [
	{
		name: '_uniqueIdentifier',
		type: 'String',
		description: 'Unique identifier of the current record',
		insertText: '_uniqueIdentifier'
	},
	{
		name: '_facts',
		type: 'Map',
		description: 'Mapping facts/metadata (provider, baseUrl, etc.)',
		insertText: '_facts'
	},
	{
		name: '_optLookup',
		type: 'Map',
		description: 'Controlled vocabularies from record definition',
		insertText: '_optLookup'
	}
];

// Function categories
export const functionCategories: FunctionCategory[] = [
	{
		name: 'Text Methods',
		description: 'Methods available on nodes and strings',
		functions: [
			{
				name: '.text()',
				signature: 'node.text()',
				description: 'Get text content of a node',
				example: 'input.record.title.text()',
				insertText: '.text()'
			},
			{
				name: '.sanitize()',
				signature: 'string.sanitize()',
				description: 'Remove newlines and collapse multiple spaces',
				example: 'input.record.title.text().sanitize()',
				insertText: '.sanitize()'
			},
			{
				name: '.sanitizeURI()',
				signature: 'string.sanitizeURI()',
				description: 'URL-encode special characters (space, brackets, backslash)',
				example: 'input.record.url.text().sanitizeURI()',
				insertText: '.sanitizeURI()'
			},
			{
				name: '.trim()',
				signature: 'string.trim()',
				description: 'Remove leading and trailing whitespace',
				example: 'input.record.title.text().trim()',
				insertText: '.trim()'
			},
			{
				name: '.toLowerCase()',
				signature: 'string.toLowerCase()',
				description: 'Convert to lowercase',
				insertText: '.toLowerCase()'
			},
			{
				name: '.toUpperCase()',
				signature: 'string.toUpperCase()',
				description: 'Convert to uppercase',
				insertText: '.toUpperCase()'
			}
		]
	},
	{
		name: 'String Operations',
		description: 'String manipulation methods',
		functions: [
			{
				name: '.split()',
				signature: 'string.split(regex)',
				description: 'Split string by regex pattern',
				example: '"a,b,c".split(",")',
				insertText: '.split(",")'
			},
			{
				name: '.replaceAll()',
				signature: 'string.replaceAll(regex, replacement)',
				description: 'Replace all matches of pattern',
				example: 'title.replaceAll("\\\\s+", " ")',
				insertText: '.replaceAll("", "")'
			},
			{
				name: '.contains()',
				signature: 'string.contains(substring)',
				description: 'Check if string contains substring',
				example: 'if (title.contains("museum"))',
				insertText: '.contains("")'
			},
			{
				name: '.startsWith()',
				signature: 'string.startsWith(prefix)',
				description: 'Check if string starts with prefix',
				insertText: '.startsWith("")'
			},
			{
				name: '.endsWith()',
				signature: 'string.endsWith(suffix)',
				description: 'Check if string ends with suffix',
				insertText: '.endsWith("")'
			},
			{
				name: '.substring()',
				signature: 'string.substring(from, to?)',
				description: 'Extract substring from position',
				example: 'title.substring(0, 10)',
				insertText: '.substring(0, )'
			},
			{
				name: '.indexOf()',
				signature: 'string.indexOf(substring)',
				description: 'Find position of substring (-1 if not found)',
				insertText: '.indexOf("")'
			}
		]
	},
	{
		name: 'Node Navigation',
		description: 'Navigate XML structure',
		functions: [
			{
				name: '.parent()',
				signature: 'node.parent()',
				description: 'Get parent node',
				insertText: '.parent()'
			},
			{
				name: '.getChildren()',
				signature: 'node.getChildren()',
				description: 'Get all child nodes',
				insertText: '.getChildren()'
			},
			{
				name: '.attributes()',
				signature: 'node.attributes()',
				description: 'Get node attributes as Map',
				example: 'node.attributes()["lang"]',
				insertText: '.attributes()'
			},
			{
				name: '["@attr"]',
				signature: 'node["@attributeName"]',
				description: 'Get attribute value',
				example: 'input.record.title["@lang"]',
				insertText: '["@"]'
			},
			{
				name: '.getValueNodes()',
				signature: 'node.getValueNodes(name)',
				description: 'Recursively find all nodes with name that have text',
				example: 'input.getValueNodes("subject")',
				insertText: '.getValueNodes("")'
			}
		]
	},
	{
		name: 'List Operators',
		description: 'Special operators for working with lists of nodes',
		functions: [
			{
				name: '* (each)',
				signature: 'list * { closure }',
				description: 'Apply closure to EACH element in list',
				example: 'input.record.subject * { it.sanitize() }',
				insertText: ' * { it }'
			},
			{
				name: '** (first)',
				signature: 'list ** { closure }',
				description: 'Apply closure to FIRST element only',
				example: 'input.record.title ** { it.toUpperCase() }',
				insertText: ' ** { it }'
			},
			{
				name: '>> (once)',
				signature: 'list >> { closure }',
				description: 'Apply closure ONCE with entire list as parameter',
				example: 'input.record.subject >> { all -> all.join(", ") }',
				insertText: ' >> { all -> all }'
			},
			{
				name: '| (tuple)',
				signature: 'list1 | list2',
				description: 'Create paired tuples from two lists',
				example: 'keys | values',
				insertText: ' | '
			},
			{
				name: '+ (concat)',
				signature: 'list1 + list2',
				description: 'Concatenate two lists',
				insertText: ' + '
			}
		]
	},
	{
		name: 'Date Functions',
		description: 'Date calculation functions',
		functions: [
			{
				name: 'calculateAge()',
				signature: 'calculateAge(birthDate, deathDate, autoReorder?, ignoreErrors?)',
				description: 'Calculate age in years from two dates',
				example: 'calculateAge(input.birthDate, input.deathDate)',
				insertText: 'calculateAge(, )'
			},
			{
				name: 'calculateAgeRange()',
				signature: 'calculateAgeRange(birthDate, deathDate, autoReorder?, ignoreErrors?)',
				description: 'Calculate age range (e.g., "20 – 29")',
				example: 'calculateAgeRange(input.birthDate, input.deathDate)',
				insertText: 'calculateAgeRange(, )'
			}
		]
	},
	{
		name: 'Record Control',
		description: 'Control record processing flow',
		functions: [
			{
				name: 'discard()',
				signature: 'discard(reason)',
				description: 'Skip this record entirely',
				example: 'discard("Missing required field")',
				insertText: 'discard("")'
			},
			{
				name: 'discardIf()',
				signature: 'discardIf(condition, reason)',
				description: 'Skip record if condition is true',
				example: 'discardIf(!title, "No title")',
				insertText: 'discardIf(, "")'
			},
			{
				name: 'discardIfNot()',
				signature: 'discardIfNot(condition, reason)',
				description: 'Skip record if condition is false',
				example: 'discardIfNot(title, "Title required")',
				insertText: 'discardIfNot(, "")'
			}
		]
	},
	{
		name: 'Conditionals',
		description: 'Conditional expressions',
		functions: [
			{
				name: '?: (ternary)',
				signature: 'condition ? valueIfTrue : valueIfFalse',
				description: 'Conditional expression',
				example: 'title ? title : "Untitled"',
				insertText: ' ? : '
			},
			{
				name: '?: (elvis)',
				signature: 'value ?: default',
				description: 'Return value if truthy, otherwise default',
				example: 'title ?: "Untitled"',
				insertText: ' ?: ""'
			},
			{
				name: '?. (safe nav)',
				signature: 'object?.property',
				description: 'Safe navigation - returns null if object is null',
				example: 'input.record?.title?.text()',
				insertText: '?.'
			}
		]
	}
];

// Helper to generate source path variables from a mapping's source path
export function generatePathVariables(sourcePath: string): VariableDoc[] {
	if (!sourcePath) return [];

	const parts = sourcePath.split('/').filter(p => p && p !== 'input');
	const variables: VariableDoc[] = [];

	let currentPath = 'input';
	for (const part of parts) {
		currentPath += '.' + part;
		variables.push({
			name: currentPath,
			type: 'GroovyNode',
			description: `Access ${part} element`,
			insertText: currentPath
		});
	}

	return variables;
}

// Custom function/variable that can be defined per mapping or rec-def
export interface CustomFunction {
	name: string;
	signature?: string;
	description: string;
	example?: string;
	insertText: string;
	category?: string; // Optional category for grouping
}

export interface CustomVariable {
	name: string;
	type?: string;
	description: string;
	insertText: string;
}

// Combine all variables for display
export function getAllVariables(
	sourcePath: string,
	customVariables: CustomVariable[] = []
): VariableDoc[] {
	return [
		...defaultVariables,
		...generatePathVariables(sourcePath),
		...customVariables.map(cv => ({
			name: cv.name,
			type: cv.type ?? 'String',
			description: cv.description,
			insertText: cv.insertText
		}))
	];
}

// Combine all functions for display, including custom ones
export function getAllFunctionCategories(
	customFunctions: CustomFunction[] = []
): FunctionCategory[] {
	const categories = [...functionCategories];

	// Group custom functions by category
	const customByCategory = new Map<string, CustomFunction[]>();
	for (const fn of customFunctions) {
		const cat = fn.category ?? 'Custom Functions';
		if (!customByCategory.has(cat)) {
			customByCategory.set(cat, []);
		}
		customByCategory.get(cat)!.push(fn);
	}

	// Add custom categories
	for (const [catName, functions] of customByCategory) {
		const existingCat = categories.find(c => c.name === catName);
		if (existingCat) {
			// Add to existing category
			existingCat.functions.push(...functions.map(fn => ({
				name: fn.name,
				signature: fn.signature ?? fn.name,
				description: fn.description,
				example: fn.example,
				insertText: fn.insertText
			})));
		} else {
			// Create new category
			categories.push({
				name: catName,
				description: 'Custom functions from mapping or rec-def',
				functions: functions.map(fn => ({
					name: fn.name,
					signature: fn.signature ?? fn.name,
					description: fn.description,
					example: fn.example,
					insertText: fn.insertText
				}))
			});
		}
	}

	return categories;
}
