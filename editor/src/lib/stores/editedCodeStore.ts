import { writable, derived, get } from 'svelte/store';

/**
 * Store for tracking locally edited Groovy code until Save.
 * Each mapping can have custom code that overrides the generated code.
 *
 * Key: mapping ID or output path (unique identifier for the mapping)
 * Value: The edited Groovy code string
 */

// Type for the edited code state
export interface EditedCodeState {
	// Map of outputPath -> edited code
	codes: Map<string, string>;
	// Track which mappings have been modified
	modifiedPaths: Set<string>;
}

// Create the edited code store
function createEditedCodeStore() {
	const store = writable<EditedCodeState>({
		codes: new Map(),
		modifiedPaths: new Set()
	});

	const { subscribe, set, update } = store;

	return {
		subscribe,

		/**
		 * Set edited code for a mapping
		 * @param outputPath The output path of the mapping (unique identifier)
		 * @param code The edited Groovy code
		 */
		setCode: (outputPath: string, code: string) => {
			update((state) => {
				const newCodes = new Map(state.codes);
				newCodes.set(outputPath, code);
				const newModified = new Set(state.modifiedPaths);
				newModified.add(outputPath);
				return {
					codes: newCodes,
					modifiedPaths: newModified
				};
			});
		},

		/**
		 * Get edited code for a mapping (read-only, safe to call from $derived)
		 * @param outputPath The output path of the mapping
		 * @returns The edited code or undefined if not edited
		 */
		getCode: (outputPath: string): string | undefined => {
			const state = get(store);
			return state.codes.get(outputPath);
		},

		/**
		 * Check if a mapping has been edited (read-only, safe to call from $derived)
		 * @param outputPath The output path of the mapping
		 */
		isModified: (outputPath: string): boolean => {
			const state = get(store);
			return state.modifiedPaths.has(outputPath);
		},

		/**
		 * Reset a mapping to its generated code (remove local edit)
		 * @param outputPath The output path of the mapping
		 */
		resetCode: (outputPath: string) => {
			update((state) => {
				const newCodes = new Map(state.codes);
				newCodes.delete(outputPath);
				const newModified = new Set(state.modifiedPaths);
				newModified.delete(outputPath);
				return {
					codes: newCodes,
					modifiedPaths: newModified
				};
			});
		},

		/**
		 * Clear all edited codes (after successful save)
		 */
		clearAll: () => {
			set({
				codes: new Map(),
				modifiedPaths: new Set()
			});
		},

		/**
		 * Get all edited codes as an array for save payload (read-only)
		 * @returns Array of { outputPath, groovyCode } objects
		 */
		getAllEdited: (): Array<{ outputPath: string; groovyCode: string }> => {
			const state = get(store);
			return Array.from(state.codes.entries()).map(([outputPath, groovyCode]) => ({
				outputPath,
				groovyCode
			}));
		},

		/**
		 * Check if there are any unsaved edits (read-only)
		 */
		hasUnsavedEdits: (): boolean => {
			const state = get(store);
			return state.modifiedPaths.size > 0;
		},

		/**
		 * Get the count of modified mappings (read-only)
		 */
		getModifiedCount: (): number => {
			const state = get(store);
			return state.modifiedPaths.size;
		}
	};
}

export const editedCodeStore = createEditedCodeStore();

/**
 * Derived store that returns true if there are any unsaved edits
 */
export const hasUnsavedEdits = derived(editedCodeStore, ($store) => $store.modifiedPaths.size > 0);

/**
 * Derived store that returns the count of modified mappings
 */
export const modifiedCount = derived(editedCodeStore, ($store) => $store.modifiedPaths.size);

/**
 * Derived store that returns the set of modified paths for reactive updates
 */
export const modifiedPaths = derived(editedCodeStore, ($store) => $store.modifiedPaths);
