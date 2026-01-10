import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
	preprocess: vitePreprocess(),

	kit: {
		adapter: adapter({
			// Build output goes to narthex public/editor directory
			pages: '../public/editor',
			assets: '../public/editor',
			fallback: 'index.html',
			precompress: false,
			strict: true
		}),
		paths: {
			// Base path for all routes
			base: '/narthex/editor'
		},
		// Prerender all pages as static
		prerender: {
			entries: ['*']
		}
	}
};

export default config;
