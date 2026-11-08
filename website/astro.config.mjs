import { defineConfig } from 'astro/config';

// 官网独立托管在 infinityware.work 根路径。
export default defineConfig({
  site: 'https://infinityware.work',
  trailingSlash: 'always',
  build: { format: 'directory' },
  devToolbar: { enabled: false },
});
