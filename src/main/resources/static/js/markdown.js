// Tiny CommonMark-ish renderer for Help → Connect & API. Supports the subset
// the asset actually uses: ATX headings (# ## ###), fenced code blocks with a
// language tag, GFM-style tables, unordered/ordered lists, blockquotes, thematic
// breaks, paragraphs, inline `code`, **bold**, *italics*, and [text](url) links.
// All text is HTML-escaped before any inline markup is applied; raw HTML in the
// source is treated as content.

const esc = s => String(s ?? '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

// Inline rules run on already-escaped text — patterns match the escaped form.
function inline(text) {
    return esc(text)
        .replace(/`([^`]+)`/g, (_, code) => `<code>${code}</code>`)
        .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
        .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
        .replace(/\[([^\]]+)]\(([^)\s]+)\)/g, (_, label, url) =>
            `<a href="${url}" rel="noopener noreferrer">${label}</a>`);
}

function renderTable(headerRow, alignRow, bodyRows) {
    const cells = row => row.replace(/^\||\|$/g, '').split('|').map(s => s.trim());
    const head = cells(headerRow);
    const aligns = cells(alignRow).map(spec => {
        if (/^:-+:$/.test(spec)) return 'center';
        if (/^-+:$/.test(spec)) return 'right';
        return 'left';
    });
    const th = head.map((c, i) =>
        `<th style="text-align:${aligns[i] ?? 'left'}">${inline(c)}</th>`).join('');
    const trs = bodyRows.map(row => {
        const tds = cells(row).map((c, i) =>
            `<td style="text-align:${aligns[i] ?? 'left'}">${inline(c)}</td>`).join('');
        return `<tr>${tds}</tr>`;
    }).join('');
    return `<table><thead><tr>${th}</tr></thead><tbody>${trs}</tbody></table>`;
}

export function renderMarkdown(source) {
    const lines = String(source).replace(/\r\n?/g, '\n').split('\n');
    const out = [];
    let i = 0;
    const flushParagraph = (buf) => {
        if (buf.length) out.push(`<p>${inline(buf.join(' '))}</p>`);
    };
    while (i < lines.length) {
        const line = lines[i];
        if (!line.trim()) { i++; continue; }

        // fenced code block
        const fence = line.match(/^```(\w*)\s*$/);
        if (fence) {
            const lang = fence[1] || '';
            const buf = [];
            i++;
            while (i < lines.length && !/^```\s*$/.test(lines[i])) { buf.push(lines[i]); i++; }
            if (i < lines.length) i++;
            const cls = lang ? ` class="lang-${esc(lang)}"` : '';
            out.push(`<pre><code${cls}>${esc(buf.join('\n'))}</code></pre>`);
            continue;
        }

        // ATX heading
        const heading = line.match(/^(#{1,6})\s+(.*)$/);
        if (heading) {
            const level = heading[1].length;
            out.push(`<h${level}>${inline(heading[2].trim())}</h${level}>`);
            i++;
            continue;
        }

        // thematic break
        if (/^---+\s*$/.test(line)) { out.push('<hr>'); i++; continue; }

        // table: header | align | body — needs at least two rows
        if (/\|/.test(line) && i + 1 < lines.length && /^\s*\|?\s*:?-+/.test(lines[i + 1])) {
            const header = line;
            const align = lines[i + 1];
            const body = [];
            i += 2;
            while (i < lines.length && /\|/.test(lines[i]) && lines[i].trim()) {
                body.push(lines[i]); i++;
            }
            out.push(renderTable(header, align, body));
            continue;
        }

        // unordered/ordered list
        const listMatch = line.match(/^(\s*)([-*]|\d+\.)\s+(.*)$/);
        if (listMatch) {
            const ordered = /\d+\./.test(listMatch[2]);
            const tag = ordered ? 'ol' : 'ul';
            const items = [];
            while (i < lines.length) {
                const cur = lines[i];
                const m = cur.match(/^(\s*)([-*]|\d+\.)\s+(.*)$/);
                if (m) { items.push(m[3]); i++; continue; }
                // continuation: indented line joins the previous item
                if (/^\s+\S/.test(cur) && items.length) {
                    items[items.length - 1] += ' ' + cur.trim();
                    i++;
                    continue;
                }
                break;
            }
            out.push(`<${tag}>${items.map(t => `<li>${inline(t)}</li>`).join('')}</${tag}>`);
            continue;
        }

        // blockquote
        if (/^>\s?/.test(line)) {
            const buf = [];
            while (i < lines.length && /^>\s?/.test(lines[i])) {
                buf.push(lines[i].replace(/^>\s?/, '')); i++;
            }
            out.push(`<blockquote><p>${inline(buf.join(' '))}</p></blockquote>`);
            continue;
        }

        // paragraph: consume until blank line / new block
        const buf = [line];
        i++;
        while (i < lines.length && lines[i].trim()
                && !/^(#{1,6}\s|```|---+\s*$|[-*]\s|\d+\.\s|>\s)/.test(lines[i])
                && !(/\|/.test(lines[i]) && i + 1 < lines.length
                        && /^\s*\|?\s*:?-+/.test(lines[i + 1]))) {
            buf.push(lines[i]); i++;
        }
        flushParagraph(buf);
    }
    return out.join('\n');
}
