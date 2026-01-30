/**
 * Theme store for managing light/dark mode with system preference support.
 */

export type ThemeMode = 'light' | 'dark' | 'system';
export type ResolvedTheme = 'light' | 'dark';

const STORAGE_KEY = 'narthex-theme';

// Get initial theme from localStorage or default to 'system'
function getInitialTheme(): ThemeMode {
	if (typeof window === 'undefined') return 'system';
	const stored = localStorage.getItem(STORAGE_KEY);
	if (stored === 'light' || stored === 'dark' || stored === 'system') {
		return stored;
	}
	return 'system';
}

// Get system preference
function getSystemTheme(): ResolvedTheme {
	if (typeof window === 'undefined') return 'dark';
	return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

// Resolve theme mode to actual theme
function resolveTheme(mode: ThemeMode): ResolvedTheme {
	if (mode === 'system') {
		return getSystemTheme();
	}
	return mode;
}

// Apply theme to document
function applyTheme(theme: ResolvedTheme) {
	if (typeof document === 'undefined') return;

	const root = document.documentElement;
	if (theme === 'dark') {
		root.classList.add('dark');
		root.classList.remove('light');
	} else {
		root.classList.add('light');
		root.classList.remove('dark');
	}
}

// Create the theme store
class ThemeStore {
	private _mode = $state<ThemeMode>('system');
	private _resolved = $state<ResolvedTheme>('dark');

	constructor() {
		if (typeof window !== 'undefined') {
			this._mode = getInitialTheme();
			this._resolved = resolveTheme(this._mode);
			applyTheme(this._resolved);

			// Listen for system theme changes
			window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
				if (this._mode === 'system') {
					this._resolved = e.matches ? 'dark' : 'light';
					applyTheme(this._resolved);
				}
			});
		}
	}

	get mode(): ThemeMode {
		return this._mode;
	}

	get resolved(): ResolvedTheme {
		return this._resolved;
	}

	get isDark(): boolean {
		return this._resolved === 'dark';
	}

	setMode(mode: ThemeMode) {
		this._mode = mode;
		this._resolved = resolveTheme(mode);
		applyTheme(this._resolved);

		if (typeof localStorage !== 'undefined') {
			localStorage.setItem(STORAGE_KEY, mode);
		}
	}

	toggle() {
		// Cycle through: system -> light -> dark -> system
		const modes: ThemeMode[] = ['system', 'light', 'dark'];
		const currentIndex = modes.indexOf(this._mode);
		const nextIndex = (currentIndex + 1) % modes.length;
		this.setMode(modes[nextIndex]);
	}
}

export const themeStore = new ThemeStore();
