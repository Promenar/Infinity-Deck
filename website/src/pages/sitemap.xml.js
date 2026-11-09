import { LOCALES, href, strings } from '../i18n.mjs';
import { guidePaths } from '../guides.mjs';

export function GET({ site }) {
  const paths = ['/', '/download/', '/docs/', '/changelog/', ...guidePaths];
  const entries = paths.flatMap(path => {
    const languages = [...LOCALES.map(locale => [strings[locale].htmlLang, href(locale, path)]), ['x-default', path]];
    const alternates = languages.map(([lang, url]) =>
      `<xhtml:link rel="alternate" hreflang="${lang}" href="${new URL(url, site)}"/>`).join('');
    return LOCALES.map(locale =>
      `<url><loc>${new URL(href(locale, path), site)}</loc>${alternates}</url>`);
  });
  return new Response(`<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">
${entries.join('\n')}
</urlset>
`, { headers: { 'Content-Type': 'application/xml; charset=utf-8' } });
}
