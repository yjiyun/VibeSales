/**
 * 极简 markdown 渲染（只支持后端话术实际用到的语法）
 *
 * 后端 `WizardSpeech` 产出的 markdown 仅包含：`**加粗**`、`- 列表`、
 * 换行、以及少量 emoji。这里手写一个够用的转换器，避免为几种语法引入
 * markdown-it + DOMPurify 两个依赖。
 *
 * 安全：先整体 HTML 转义，再只把白名单语法还原成标签，
 * 因此模型返回的任何内容都不可能注入标签（`<script>` 会被转义成文本）。
 */

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** 行内语法：**加粗** / `代码`（在已转义文本上操作）。 */
function inline(text) {
  return text
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>');
}

/** markdown → 安全 HTML。 */
export function renderMarkdown(src) {
  const lines = escapeHtml(src ?? '').split('\n');
  const out = [];
  let inList = false;

  const closeList = () => {
    if (inList) {
      out.push('</ul>');
      inList = false;
    }
  };

  for (const raw of lines) {
    const line = raw.replace(/\s+$/, '');
    const bullet = /^\s*[-*·]\s+(.*)$/.exec(line);
    if (bullet) {
      if (!inList) {
        out.push('<ul>');
        inList = true;
      }
      out.push(`<li>${inline(bullet[1])}</li>`);
      continue;
    }
    closeList();
    if (!line.trim()) continue;
    // 缩进行（话术里的「  主要业务：……」）保留缩进感
    const indented = /^\s{2,}\S/.test(line);
    out.push(
      indented
        ? `<p class="md-indent">${inline(line.trim())}</p>`
        : `<p>${inline(line)}</p>`,
    );
  }
  closeList();
  return out.join('');
}

