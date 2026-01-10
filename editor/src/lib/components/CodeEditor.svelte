<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import type * as Monaco from 'monaco-editor';

	interface Props {
		value: string;
		onChange?: (value: string) => void;
		readonly?: boolean;
	}

	let { value, onChange, readonly = false }: Props = $props();

	let editorContainer: HTMLDivElement;
	let editor: Monaco.editor.IStandaloneCodeEditor | null = null;
	let monaco: typeof Monaco | null = null;

	onMount(async () => {
		// Dynamic import of Monaco
		const monacoModule = await import('monaco-editor');
		monaco = monacoModule;

		// Register Groovy language (based on Java)
		monaco.languages.register({ id: 'groovy' });

		// Groovy tokenizer (simplified, based on Java)
		monaco.languages.setMonarchTokensProvider('groovy', {
			defaultToken: '',
			tokenPostfix: '.groovy',

			keywords: [
				'abstract', 'as', 'assert', 'boolean', 'break', 'byte', 'case', 'catch',
				'char', 'class', 'const', 'continue', 'def', 'default', 'do', 'double',
				'else', 'enum', 'extends', 'false', 'final', 'finally', 'float', 'for',
				'goto', 'if', 'implements', 'import', 'in', 'instanceof', 'int', 'interface',
				'long', 'native', 'new', 'null', 'package', 'private', 'protected', 'public',
				'return', 'short', 'static', 'strictfp', 'super', 'switch', 'synchronized',
				'this', 'throw', 'throws', 'trait', 'transient', 'true', 'try', 'void',
				'volatile', 'while', 'with'
			],

			operators: [
				'=', '>', '<', '!', '~', '?', ':', '==', '<=', '>=', '!=', '&&', '||',
				'++', '--', '+', '-', '*', '/', '&', '|', '^', '%', '<<', '>>', '>>>',
				'+=', '-=', '*=', '/=', '&=', '|=', '^=', '%=', '<<=', '>>=', '>>>='
			],

			symbols: /[=><!~?:&|+\-*\/\^%]+/,

			escapes: /\\(?:[abfnrtv\\"']|x[0-9A-Fa-f]{1,4}|u[0-9A-Fa-f]{4}|U[0-9A-Fa-f]{8})/,

			tokenizer: {
				root: [
					// Identifiers and keywords
					[/[a-zA-Z_$][\w$]*/, {
						cases: {
							'@keywords': 'keyword',
							'@default': 'identifier'
						}
					}],

					// Whitespace
					{ include: '@whitespace' },

					// Delimiters and operators
					[/[{}()\[\]]/, '@brackets'],
					[/[<>](?!@symbols)/, '@brackets'],
					[/@symbols/, {
						cases: {
							'@operators': 'operator',
							'@default': ''
						}
					}],

					// Numbers
					[/\d*\.\d+([eE][\-+]?\d+)?[fFdD]?/, 'number.float'],
					[/0[xX][0-9a-fA-F]+[lL]?/, 'number.hex'],
					[/\d+[lL]?/, 'number'],

					// Delimiter
					[/[;,.]/, 'delimiter'],

					// Strings
					[/"([^"\\]|\\.)*$/, 'string.invalid'],
					[/"/, 'string', '@string_double'],
					[/'([^'\\]|\\.)*$/, 'string.invalid'],
					[/'/, 'string', '@string_single'],

					// GString interpolation
					[/\$\{/, 'string.interpolation', '@interpolation'],
					[/\$\w+/, 'string.interpolation'],
				],

				whitespace: [
					[/[ \t\r\n]+/, ''],
					[/\/\*/, 'comment', '@comment'],
					[/\/\/.*$/, 'comment'],
				],

				comment: [
					[/[^\/*]+/, 'comment'],
					[/\*\//, 'comment', '@pop'],
					[/[\/*]/, 'comment']
				],

				string_double: [
					[/[^\\"$]+/, 'string'],
					[/@escapes/, 'string.escape'],
					[/\\./, 'string.escape.invalid'],
					[/\$\{/, 'string.interpolation', '@interpolation'],
					[/\$\w+/, 'string.interpolation'],
					[/"/, 'string', '@pop']
				],

				string_single: [
					[/[^\\']+/, 'string'],
					[/@escapes/, 'string.escape'],
					[/\\./, 'string.escape.invalid'],
					[/'/, 'string', '@pop']
				],

				interpolation: [
					[/\}/, 'string.interpolation', '@pop'],
					{ include: 'root' }
				]
			}
		});

		// Groovy language configuration
		monaco.languages.setLanguageConfiguration('groovy', {
			comments: {
				lineComment: '//',
				blockComment: ['/*', '*/']
			},
			brackets: [
				['{', '}'],
				['[', ']'],
				['(', ')']
			],
			autoClosingPairs: [
				{ open: '{', close: '}' },
				{ open: '[', close: ']' },
				{ open: '(', close: ')' },
				{ open: '"', close: '"' },
				{ open: "'", close: "'" }
			],
			surroundingPairs: [
				{ open: '{', close: '}' },
				{ open: '[', close: ']' },
				{ open: '(', close: ')' },
				{ open: '"', close: '"' },
				{ open: "'", close: "'" }
			]
		});

		// Define dark theme for Groovy
		monaco.editor.defineTheme('groovy-dark', {
			base: 'vs-dark',
			inherit: true,
			rules: [
				{ token: 'keyword', foreground: 'c586c0' },
				{ token: 'string', foreground: 'ce9178' },
				{ token: 'string.interpolation', foreground: '4ec9b0' },
				{ token: 'number', foreground: 'b5cea8' },
				{ token: 'comment', foreground: '6a9955' },
				{ token: 'operator', foreground: 'd4d4d4' },
				{ token: 'identifier', foreground: '9cdcfe' }
			],
			colors: {
				'editor.background': '#0a0a0a',
				'editor.foreground': '#d4d4d4',
				'editor.lineHighlightBackground': '#1a1a1a',
				'editorCursor.foreground': '#ffffff',
				'editor.selectionBackground': '#264f78',
				'editorLineNumber.foreground': '#5a5a5a',
				'editorLineNumber.activeForeground': '#c6c6c6'
			}
		});

		// Create editor
		editor = monaco.editor.create(editorContainer, {
			value: value,
			language: 'groovy',
			theme: 'groovy-dark',
			readOnly: readonly,
			minimap: { enabled: false },
			fontSize: 13,
			fontFamily: "'JetBrains Mono', 'Fira Code', 'SF Mono', Consolas, monospace",
			lineNumbers: 'on',
			scrollBeyondLastLine: false,
			automaticLayout: true,
			tabSize: 4,
			insertSpaces: false,
			wordWrap: 'off',
			folding: true,
			renderLineHighlight: 'line',
			scrollbar: {
				vertical: 'auto',
				horizontal: 'auto',
				verticalScrollbarSize: 10,
				horizontalScrollbarSize: 10
			},
			padding: { top: 8, bottom: 8 }
		});

		// Listen for changes
		editor.onDidChangeModelContent(() => {
			const newValue = editor?.getValue() ?? '';
			onChange?.(newValue);
		});
	});

	onDestroy(() => {
		editor?.dispose();
	});

	// Update editor value when prop changes
	// Always read `value` first to ensure tracking, even if editor isn't ready yet
	$effect(() => {
		const newValue = value; // Read prop to track it
		if (editor && editor.getValue() !== newValue) {
			editor.setValue(newValue);
		}
	});
</script>

<div bind:this={editorContainer} class="editor-container"></div>

<style>
	.editor-container {
		width: 100%;
		height: 100%;
	}
</style>
