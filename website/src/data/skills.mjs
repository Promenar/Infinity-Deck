export const title = { 'zh-CN': '官方目录', en: 'Official catalog' };
export const categories = [
  { id: 'writing', title: { 'zh-CN': '写作', en: 'Writing' } },
  { id: 'research', title: { 'zh-CN': '调研', en: 'Research' } },
  { id: 'data', title: { 'zh-CN': '数据', en: 'Data' } },
  { id: 'dev', title: { 'zh-CN': '开发', en: 'Development' } },
  { id: 'design', title: { 'zh-CN': '设计', en: 'Design' } },
];
export const skills = [
  {
    name: 'meeting-summary', version: '1.0.0', category: 'writing',
    channels: ['sideload', 'play'], author: 'Infinity Deck', license: 'MIT',
    title: { 'zh-CN': '会议纪要', en: 'Meeting summary' },
    summary: { 'zh-CN': '整理会议记录与长文，提取决议、待办与未决问题。', en: 'Turn meeting notes and long texts into decisions, action items, and open questions.' },
  },
  {
    name: 'writing-polish', version: '1.0.0', category: 'writing',
    channels: ['sideload', 'play'], author: 'Infinity Deck', license: 'MIT',
    title: { 'zh-CN': '中文润色', en: 'Chinese polish' },
    summary: { 'zh-CN': '修改病句与标点，统一术语，调整语气，并标出每处改动。', en: 'Fix grammar and punctuation, unify terms, adjust tone, and mark every edit.' },
  },
  {
    name: 'markitdown', version: '1.0.0', category: 'writing',
    channels: ['sideload'], author: 'Infinity Deck', license: 'MIT',
    title: { 'zh-CN': '文档转 Markdown', en: 'Documents to Markdown' },
    summary: { 'zh-CN': '用 MarkItDown 把 PDF、Office、网页等文档批量转为 Markdown。', en: 'Batch-convert PDF, Office, and web documents to Markdown with MarkItDown.' },
  },
  {
    name: 'research-brief', version: '1.0.0', category: 'research',
    channels: ['sideload', 'play'], author: 'Infinity Deck', license: 'MIT',
    title: { 'zh-CN': '调研简报', en: 'Research brief' },
    summary: { 'zh-CN': '联网检索、多源核验，输出标注来源与时效的调研简报。', en: 'Search the web, cross-check sources, and deliver a dated, cited brief.' },
  },
  {
    name: 'business-analysis', version: '1.0.0', category: 'research',
    channels: ['sideload', 'play'], author: 'Infinity Deck（改编自 anthropics/knowledge-work-plugins）', license: 'Apache-2.0',
    title: { 'zh-CN': '经营分析', en: 'Business analysis' },
    summary: { 'zh-CN': '差异分解、财务报表、竞品简报与用户研究综合。', en: 'Variance analysis, financial statements, competitive briefs, and research synthesis.' },
  },
  {
    name: 'data-analysis', version: '1.0.0', category: 'data',
    channels: ['sideload', 'play'], author: 'Infinity Deck（改编自 anthropics/knowledge-work-plugins）', license: 'Apache-2.0',
    title: { 'zh-CN': '数据分析', en: 'Data analysis' },
    summary: { 'zh-CN': '探查、统计、SQL 查询、出图与看板，分享前复核结论。', en: 'Explore, analyze, query with SQL, chart, build dashboards, and validate findings.' },
  },
  {
    name: 'data-storytelling', version: '1.0.0', category: 'data',
    channels: ['sideload', 'play'], author: 'Infinity Deck（改编自 oh-da/dataink）', license: 'MIT',
    title: { 'zh-CN': '数据叙事', en: 'Data storytelling' },
    summary: { 'zh-CN': '设计汇报叙事、选图、看板与表格，评审图表与配色。', en: 'Shape data narratives, choose charts, lay out dashboards and tables, and review visuals.' },
  },
  {
    name: 'code-review', version: '1.0.0', category: 'dev',
    channels: ['sideload', 'play'], author: 'Infinity Deck', license: 'MIT',
    title: { 'zh-CN': '代码评审', en: 'Code review' },
    summary: { 'zh-CN': '按清单评审代码，输出按严重度排序、可定位的问题。', en: 'Review code against a checklist and report located issues by severity.' },
  },
  {
    name: 'spec-intake', version: '1.0.0', category: 'dev',
    channels: ['sideload', 'play'], author: 'Infinity Deck', license: 'MIT',
    title: { 'zh-CN': '需求澄清', en: 'Spec intake' },
    summary: { 'zh-CN': '逐轮澄清模糊需求，产出含验收标准的规格。', en: 'Clarify vague requests round by round into a spec with acceptance criteria.' },
  },
  {
    name: 'mcp-development', version: '1.0.0', category: 'dev',
    channels: ['sideload'], author: 'Infinity Deck（改编自 anthropics/claude-plugins-official）', license: 'Apache-2.0',
    title: { 'zh-CN': 'MCP 开发', en: 'MCP development' },
    summary: { 'zh-CN': '设计、构建、打包 MCP 服务器，并在应用中联调。', en: 'Design, build, and package MCP servers, and test them in the app.' },
  },
  {
    name: 'frontend-design', version: '1.0.0', category: 'design',
    channels: ['sideload', 'play'], author: 'Infinity Deck（改编自 anthropics/claude-plugins-official）', license: 'Apache-2.0',
    title: { 'zh-CN': '前端设计', en: 'Frontend design' },
    summary: { 'zh-CN': '确定有辨识度的视觉方向，在画布中实现页面与原型。', en: 'Set a distinctive visual direction and build pages and prototypes on the canvas.' },
  },
];
