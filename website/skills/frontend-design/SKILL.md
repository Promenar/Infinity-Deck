---
name: frontend-design
description: 前端视觉设计指南：新建或改造网页、应用界面、落地页、原型时，确定有辨识度的视觉方向、字体与排版、配色、布局与动效，避免模板化的“AI 默认风格”，并按先规划、对照需求复核、再实现、最后自评的流程交付；也涵盖界面文案写法。用于“设计一个页面/落地页/原型”“让界面更有设计感”“重新设计这个界面”。Frontend design, UI design, landing page, visual direction, typography, distinctive design.
license: Apache-2.0
---

# Frontend Design

## 本应用适配（Infinity Deck）

下文为上游英文原文，设计原则全部适用；在本应用中实现时遵守以下环境约束，冲突时以本节为准。

- **在哪里做**：单页用 `canvas_create`（`kind` 为 `page`，传 `html`）；多屏、可点击跳转的 App 或网页原型用 `kind: "prototype"` 并传 `screens`，屏间用 `<a href="infinity-screen:<屏幕 ID>">` 链接。之后用 `canvas_update` 修改，小改动优先用补丁；补丁匹配失败时先用 `canvas_read` 读取当前源码。用户要求交付项目代码时，按其技术栈写入工作区文件。
- **画布不能联网**：外部脚本、样式表、网页字体（包括 Google Fonts）、图片请求都会被拦截。CSS 与 JS 必须内联；图片与字体只能用 data: URI，整页 UTF-8 不超过 1 MiB。
- **字体**：没有内嵌字体时只能使用设备字体，用 `serif`、`sans-serif`、`monospace` 等通用族名组织字体栈，中文会落到系统中文字体。需要特定字体时，取得用户许可的字体文件（例如用户提供的或开源授权的子集化字体），转为 base64 后用 `@font-face { src: url(data:font/woff2;base64,...) }` 内嵌，注意总大小。没有可用字体时，用字号、字重、字距、行高和版式建立个性，不要假装已加载某个字体。
- **视口**：页面须在 320–420 CSS px 宽的视口内可读；宽表格放进 `overflow-x: auto` 的容器或允许换行，不要使用超过容器的固定宽度。下文的 hero、排版与布局建议按手机屏幕落实。
- **深浅色**：页面随应用浅色或暗色主题显示。要自行适配暗色时声明 `<meta name="color-scheme" content="light dark">`，并用 `prefers-color-scheme` 成对设置背景与文字颜色；给元素设背景色时同时设文字颜色。未声明的页面按浅色排版，暗色主题下由系统自动变暗。
- **图像素材**：需要插图或照片时可以用 `image_generation` 生成，或使用用户提供的图片；放进画布前要转成 data: URI（例如用 `python_exec` 的 `base64` 模块读取工作区图片），并控制尺寸。
- **自评**：本应用不能对画布截图。下文“taking screenshots to review”一步改为：用 `canvas_read` 通读源码，逐项核对窄屏布局、对比度、焦点样式、减少动效偏好与暗色下的颜色配对，并请用户在画布中查看后反馈。
- **既有偏好**：下文提到的“memory”指对话、项目规则或用户已给出的品牌与风格偏好。

## Design lead brief

Approach this as the design lead at a design studio known for giving every client a distinct visual identity that is not mistaken for anyone else's. This client has already rejected proposals that felt cliché or templated, and is paying for a distinctive point of view: make deliberate, opinionated choices about palette, typography, and layout that are specific to this brief, and take aesthetic risk if justified.

## Ground your designs in the subject matter

If the brief does not identify what the product or subject matter is, identify it yourself before designing, and confirm with the client. You can come up with one concrete subject, the design's audience, and the design's primary job, as a proposal. If there's any information in your memory about the client's preferences or context about what they're building, use that as a hint. The subject's industry, subject matter, materials, and vernacular are where distinctive visual choices come from — a design for a toy for girls aged 8–11 will be very aesthetically different from a dashboard for financial analysts. Build with the brief's real content and subject matter throughout.

## Design principles

For web designs, the hero is the first thing viewers will see. Open with the most characteristic thing in the subject's world, in the form that is most appropriate: a headline, an image, an animation, a live demo, an interactive moment, or other treatments. Be deliberate with your choice: a big number with a small label, supporting stats, and a gradient accent is the default treatment, so only use it if that's truly the best option.

Typography carries the personality of the page. You don't need a different typeface for display or headline text and body content: use one family or two, and if two, make them clearly distinct.

Choose your typefaces deliberately, not the default families you would reach for on any other project, and set a clear type scale following the default guidance of The Elements of Typographic Style with intentional weights, widths, and spacing. When type is used as a headline or visual element, use the type treatment itself as an active part of the design, not a neutral delivery vehicle for the content.

Default to line lengths of less than 80 characters. Serif typefaces can have slightly longer line lengths; give serif body text slightly more line-height than a sans-serif.

Avoid these default typographic treatments; they are the commonest tells of a generated page:
- Accenting just a single word or phrase in a headline, like putting one word in italic/bold or a different color.
- Using all caps for labels.
- Adding unnecessary typographic labels above content.

Visual structure is information. Structural devices like outlines, borders, numbering, eyebrows, dividers, labels, etc., encode useful information about the content rather than decorate it. Many generic designs use numbered markers (01 / 02 / 03), but that's only appropriate if the content actually is a sequence — like a stepped process or a timeline. Before adding numbered markers, check the content really is a sequence.

Use non-user-triggered motion sparingly and deliberately, only to draw attention. A single orchestrated moment — one page-load sequence or one reveal — lands better than scattered effects; fade-and-slide-up entrances on each section and hover transitions on every card are the generic default and read as AI-generated. Motion that answers a person's action (opening, expanding, confirming) is welcome when it shows what changed.

Consider written content carefully. Often a design brief may not contain real content, and it's up to you to come up with copy and placeholder content. Copy can make a design feel as templated as the design itself. See the below section on writing for more guidance.

## Process: plan, review against the brief, build, critique

For calibration, AI-generated design right now clusters around some traits:
1. a warm cream background (near #F4F1EA) with a high-contrast serif display and a terracotta or warm-clay accent (often near #D97757 — Anthropic's own Claude-interaction accent, so on a user's brief it reads as a tell);
2. a near-black background with a single bright acid-green or vermilion accent;
3. a broadsheet-style layout with hairline rules, zero border-radius, and dense newspaper-like columns;
4. the SaaS-card kit: content chopped into identical rounded cards, one border-radius on everything regardless of hierarchy, the same soft grey shadow (rgba(0,0,0,.1)) under each, and gradient washes as decoration;
5. template chrome that appears whatever the subject: a tracked-out ALL-CAPS eyebrow label above every heading; meta strings joined with middle dots ('A · B · C'); labels built as 'WORD — fragment' with a spaced em dash; tinted near-black (#0B0B0B, #111) standing in for black; a monospace face for small data labels; a '→' appended to link and button text.

All traits are legitimate for some briefs, but they are defaults rather than choices, and they appear regardless of subject. Where the brief pins down a visual direction, follow it exactly — the brief's own words always win, including when it asks for one of these looks. Where it leaves an axis free, don't spend that freedom on one of these defaults. As with a hired human designer, there's often a careful balance between doing what you're good at and taking each project as a chance to experiment and learn.

Work in two passes. First, brainstorm a short design plan based on the client's design brief: create a compact token system with color, type, layout, and principles.
- Color: describe the core base palette as 4–6 named hex values.
- Type: the typefaces and their roles.
- Layout: a layout concept, using one-sentence prose descriptions and ASCII wireframes to ideate and compare. Include alignment guidance; should the content be left aligned, center aligned, justified?
- Principles: the high-level guidance for what makes this page unique.

Then review that plan against the brief before building: if any part of it reads like the generic default you would produce for any similar page (work through a similar prompt to see if you arrive somewhere similar) rather than a choice made for this specific brief — revise that part, say what you changed and why. Only after you've confirmed the relative uniqueness of your design plan should you start to write the code, following the revised plan.

When writing the code, be careful of structuring your CSS selector specificities. It's easy to generate CSS classes that cancel each other out (especially with a type-based selector like .section and an element-based selector like .cta). This can happen often with padding/margin between sections.

## Restraint and self-critique

Spend your boldness in one place. Let one element be the memorable thing, keep everything around it quiet and disciplined, and cut any decoration that does not serve the brief. Build to a quality floor without announcing it: responsive down to mobile, visible keyboard focus, reduced motion respected, visually accessible, harmonious color palettes. Critique your own work as you build, taking screenshots to review if your environment supports it — a picture is worth 1000 tokens. Consider Chanel's advice: before leaving the house, take a look in the mirror and remove one accessory. Human creatives have memory and always try to do something new, so if you have a space to quickly jot down notes about what you've tried, it can help you in future passes.

## More on writing in design

Words appear in a design for one reason: to make it easier to understand and use. They are design content, not decoration. Bring the same intentionality and minimalism to copywriting that you would bring to spacing and color. Before writing anything, ask what the design needs to say, and how it can best be said to help the person navigate the experience.

Write from the end user's perspective. Name things by what users will understand in simple language, not by how the system is built. A user manages notifications, not webhook config. Describe what something is or does in plain terms rather than selling it. Being specific and legible to new users is always better than being clever.

Use active voice as default. A CTA says exactly what happens when it is used: "Save changes," not "Submit." An action keeps the same name through the whole flow, so the button that says "Publish" produces a toast that says "Published." The vocabulary of an interface is the signposting for someone navigating the product. Cohesion and consistency are how people learn their way around.

Treat failure and emptiness as moments for direction, not mood. Explain what went wrong and how to fix it, in the interface's voice rather than a person's. Errors don't apologize, and they are never vague about what happened. An empty screen is an invitation to act.

Keep the tone conversational: plain verbs, sentence case, no filler, with tone matched to the brand and the audience. Let each written element do exactly one job.
