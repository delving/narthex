import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

export default defineConfig({
	plugins: [
		tailwindcss(),
		sveltekit()
	],
	server: {
		// Proxy API calls to Narthex backend during development
		proxy: {
			'/narthex/app': {
				target: 'http://localhost:9000',
				changeOrigin: true
			},
			'/narthex/api': {
				target: 'http://localhost:9000',
				changeOrigin: true
			}
		}
	}
});
