export function GET({ site }) {
  return new Response(`User-agent: *
Allow: /

Sitemap: ${new URL('/sitemap.xml', site)}
`, { headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
}
