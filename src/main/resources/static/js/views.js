// View renderers. Each returns nothing; they write into #view.
import { api, apiYaml } from './api.js';
import { runPreview } from './preview.js';

const view = () => document.getElementById('view');

/** Non-blocking error banner (e.g. enable-name conflicts) instead of raw alerts. */
export function flash(message) {
    document.querySelector('.flash')?.remove();
    const banner = document.createElement('div');
    banner.className = 'flash';
    banner.textContent = `⚠️ ${message}`;
    view().prepend(banner);
    setTimeout(() => banner.remove(), 6000);
}

async function op(action, refresh) {
    try {
        await action();
    } catch (e) {
        flash(e.message);
    }
    refresh();
}
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const fmtTime = t => t ? new Date(t).toLocaleString() : '—';
const duration = (a, b) => (a && b) ? `${((new Date(b) - new Date(a)) / 1000).toFixed(2)}s` : '';
const statusBadge = s => `<span class="status ${esc(s)}">${esc(s)}</span>`;

function triggerKind(definition) {
    const t = definition?.trigger || {};
    if (t.cron) return `cron <code>${esc(t.cron)}</code>`;
    if (t.webhook) return `webhook <code>/hooks/${esc(t.webhook.path)}</code>`;
    if (t.poll) return `poll <code>${esc(t.poll.interval)}</code>`;
    if (t.rss) return `rss <code>${esc(t.rss.interval)}</code>`;
    return '—';
}

export async function workflowList() {
    const workflows = await api('/api/workflows');
    const lastRuns = await Promise.all(workflows.map(w =>
        api(`/api/executions?workflowId=${w.id}&size=1`).then(list => list[0] ?? null)));

    const rows = workflows.map((w, i) => {
        const last = lastRuns[i];
        const subBadge = w.subscribable
            ? ` <span class="status enabled" title="recipients can subscribe to this workflow via the bot">📋 subscribable</span>`
            : '';
        return `<tr>
            <td><a href="#/wf/${w.id}">${esc(w.name)}</a>${subBadge}</td>
            <td>${triggerKind(w.definition)}</td>
            <td><span class="status ${w.enabled ? 'enabled' : 'disabled'}">${w.enabled ? 'enabled' : 'disabled'}</span></td>
            <td>${last ? `<a href="#/exec/${last.id}">${statusBadge(last.status)}</a> <span class="muted">${fmtTime(last.createdAt)}</span>` : '<span class="muted">never</span>'}</td>
            <td>
                <button data-run="${w.id}" ${w.enabled ? '' : 'disabled'}>Run</button>
                ${w.enabled
                    ? `<button class="danger" data-disable="${w.id}">Disable</button>`
                    : `<button data-enable="${w.id}">Enable</button>`}
            </td>
        </tr>`;
    }).join('');

    view().innerHTML = `<h1>Workflows</h1>
        <div class="toolbar"><a class="btn" href="#/new">＋ New workflow</a></div>
        ${workflows.length === 0 ? '<div class="empty">No workflows yet — create one with the editor</div>' : `
        <table>
            <thead><tr><th>Name</th><th>Trigger</th><th>State</th><th>Last run</th><th></th></tr></thead>
            <tbody>${rows}</tbody>
        </table>`}`;

    view().querySelectorAll('[data-run]').forEach(b => b.onclick = () =>
        op(() => api(`/api/workflows/${b.dataset.run}/run`, { method: 'POST' }), workflowList));
    view().querySelectorAll('[data-disable]').forEach(b => b.onclick = () =>
        op(() => api(`/api/workflows/${b.dataset.disable}`, { method: 'DELETE' }), workflowList));
    view().querySelectorAll('[data-enable]').forEach(b => b.onclick = () =>
        op(() => api(`/api/workflows/${b.dataset.enable}/enable`, { method: 'POST' }), workflowList));
}

export async function workflowDetail(id, page = 0) {
    const [workflow, executions, versions] = await Promise.all([
        api(`/api/workflows/${id}`),
        api(`/api/executions?workflowId=${id}&page=${page}&size=20`),
        api(`/api/workflows/${id}/versions?size=20`),
    ]);
    const subscribers = workflow.subscribable
        ? await api(`/api/workflows/${id}/subscribers`).catch(() => ({ items: [], total: 0 }))
        : { items: [], total: 0 };

    const rows = executions.map(e => `<tr>
        <td><a href="#/exec/${e.id}" class="mono">${e.id.slice(0, 8)}</a></td>
        <td>${statusBadge(e.status)}</td>
        <td>${esc(e.triggerInfo?.type ?? '')}</td>
        <td class="muted">${fmtTime(e.createdAt)}</td>
        <td class="muted">${duration(e.startedAt, e.finishedAt)}</td>
    </tr>`).join('');

    view().innerHTML = `
        <h1>${esc(workflow.name)}
            <span class="status ${workflow.enabled ? 'enabled' : 'disabled'}">${workflow.enabled ? 'enabled' : 'disabled'}</span>
        </h1>
        <div class="toolbar">
            <button id="run-btn" ${workflow.enabled ? '' : 'disabled'}>Run now</button>
            <a class="btn" href="#/wf/${id}/edit">Edit</a>
            <span class="muted">${triggerKind(workflow.definition)} · v${workflow.currentVersion}</span>
        </div>
        <div class="card">
            <h2 class="muted">Subscriptions</h2>
            <label style="display:flex; gap:0.5rem; align-items:center; cursor:pointer">
                <input type="checkbox" id="subscribable-toggle" ${workflow.subscribable ? 'checked' : ''}>
                <span><strong>Offer this workflow in the bot's /subscriptions menu</strong>
                    <span class="muted">— approved recipients can opt in to receive its notifications.
                    Pair with <code>to: subscribers</code> in a telegram step. Default off so nothing leaks.</span>
                </span>
            </label>
            ${workflow.subscribable
                ? (subscribers.total === 0
                    ? `<div class="empty">No subscribers yet — share the bot and ask people to message /subscriptions.</div>`
                    : `<ul class="muted" style="margin:0.5rem 0 0 1rem">
                        ${subscribers.items.map(s => `<li>${esc(s.displayName)} <span class="mono">${esc(s.chatIdMasked)}</span></li>`).join('')}
                       </ul>`)
                : ''}
        </div>
        <div class="card"><h2 class="muted">Definition <span class="muted">v${workflow.currentVersion}</span></h2><pre class="yaml">${esc(workflow.yamlSource)}</pre></div>
        <div class="card">
            <h2 class="muted">Versions <span class="muted">${versions.total} total</span></h2>
            ${versions.items.map(v => `
                <details class="version">
                    <summary>
                        <span class="mono">v${v.versionNo}</span>
                        ${v.versionNo === workflow.currentVersion ? '<span class="status enabled">current</span>' : ''}
                        <span class="muted">${fmtTime(v.createdAt)}${v.comment ? ' · ' + esc(v.comment) : ''}</span>
                        ${v.versionNo !== workflow.currentVersion
                            ? `<button data-rollback="${v.versionNo}">Rollback to this</button>` : ''}
                    </summary>
                    <pre class="yaml">${esc(v.yamlSource)}</pre>
                </details>`).join('')}
        </div>
        <h2>Executions <span class="muted">page ${page + 1}</span></h2>
        ${executions.length === 0 && page === 0 ? '<div class="empty">No executions yet</div>' : `
        <table>
            <thead><tr><th>Id</th><th>Status</th><th>Trigger</th><th>Created</th><th>Took</th></tr></thead>
            <tbody>${rows}</tbody>
        </table>`}
        <div class="pager">
            <button id="prev" ${page === 0 ? 'disabled' : ''}>← newer</button>
            <button id="next" ${executions.length < 20 ? 'disabled' : ''}>older →</button>
        </div>`;

    document.getElementById('subscribable-toggle').onchange = async e => {
        try {
            await api(`/api/workflows/${id}/subscribable`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ subscribable: e.target.checked }),
            });
            workflowDetail(id, page);
        } catch (err) {
            flash(err.message);
            e.target.checked = !e.target.checked;
        }
    };
    document.getElementById('run-btn').onclick = async () => {
        try {
            const r = await api(`/api/workflows/${id}/run`, { method: 'POST' });
            location.hash = `#/exec/${r.executionId}`;
        } catch (e) {
            flash(e.message);
        }
    };
    document.getElementById('prev').onclick = () => workflowDetail(id, page - 1);
    document.getElementById('next').onclick = () => workflowDetail(id, page + 1);
    view().querySelectorAll('[data-rollback]').forEach(b => b.onclick = () =>
        op(() => api(`/api/workflows/${id}/versions/${b.dataset.rollback}/rollback`, { method: 'POST' }),
                () => workflowDetail(id, 0)));
}

export async function executionDetail(id) {
    const execution = await api(`/api/executions/${id}`);
    // a WAITING step is either a durable sleep or an approval pending a decision
    const waitingNote = s => s.status !== 'WAITING' ? '' :
        s.output?.sleeping_until
            ? `<div class="muted">😴 sleeping until ${fmtTime(s.output.sleeping_until)}</div>`
            : `<div class="muted">⏳ waiting for approval, expires ${fmtTime(s.output?.expires_at)}</div>
               <div class="toolbar">
                   <button data-decide="${esc(s.name)}" data-approved="true">✅ Approve</button>
                   <button class="danger" data-decide="${esc(s.name)}" data-approved="false">❌ Deny</button>
               </div>`;
    const steps = (execution.steps ?? []).map(s => `
        <div class="step ${esc(s.status)}">
            <div>
                <div class="mono">${esc(s.name)}</div>
                ${statusBadge(s.status)}
            </div>
            <div>
                <span class="muted">attempt ${s.attempt} · ${duration(s.startedAt, s.finishedAt) || '—'}</span>
                ${waitingNote(s)}
                ${s.error ? `<div class="error">${esc(s.error)}</div>` : ''}
                ${s.output ? `<details><summary>output</summary><pre>${esc(JSON.stringify(s.output, null, 2).slice(0, 4000))}</pre></details>` : ''}
                ${s.input ? `<details><summary>input</summary><pre>${esc(JSON.stringify(s.input, null, 2).slice(0, 4000))}</pre></details>` : ''}
            </div>
            <div class="muted">${fmtTime(s.startedAt)}</div>
        </div>`).join('');

    view().innerHTML = `
        <h1><a href="#/wf/${execution.workflowId}" class="muted">workflow</a> / <span class="mono">${execution.id.slice(0, 8)}</span> ${statusBadge(execution.status)}</h1>
        <div class="card">
            <span class="muted">trigger</span> <code>${esc(execution.triggerInfo?.type)}</code>
            · <span class="muted">started</span> ${fmtTime(execution.startedAt)}
            · <span class="muted">finished</span> ${fmtTime(execution.finishedAt)}
            <details><summary class="muted">trigger payload</summary><pre>${esc(JSON.stringify(execution.triggerInfo, null, 2).slice(0, 4000))}</pre></details>
        </div>
        <div class="steps">${steps}</div>`;

    view().querySelectorAll('[data-decide]').forEach(b => b.onclick = () =>
        op(() => api(`/api/executions/${id}/steps/${encodeURIComponent(b.dataset.decide)}/decide`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ approved: b.dataset.approved === 'true' }),
        }), () => executionDetail(id)));
}

export async function dlqList() {
    const data = await api('/api/dlq?size=50');
    const rows = data.items.map(i => `<tr>
        <td class="mono">${i.id}</td>
        <td><a href="#/exec/${i.executionId}" class="mono">${i.executionId.slice(0, 8)}</a></td>
        <td class="mono">${esc(i.stepName)}</td>
        <td>${i.attempts}</td>
        <td class="error mono" style="max-width:340px">${esc((i.lastError ?? '').slice(0, 160))}</td>
        <td class="muted">${fmtTime(i.createdAt)}</td>
        <td>
            <button data-requeue="${i.id}">Requeue</button>
            <button class="danger" data-delete="${i.id}">Delete</button>
        </td>
    </tr>`).join('');

    view().innerHTML = `<h1>Dead letter queue <span class="muted">${data.total} total</span></h1>
        ${data.items.length === 0 ? '<div class="empty">DLQ is empty 🎉</div>' : `
        <table>
            <thead><tr><th>Id</th><th>Execution</th><th>Step</th><th>Attempts</th><th>Last error</th><th>When</th><th></th></tr></thead>
            <tbody>${rows}</tbody>
        </table>`}`;

    view().querySelectorAll('[data-requeue]').forEach(b => b.onclick = () =>
        op(() => api(`/api/dlq/${b.dataset.requeue}/requeue`, { method: 'POST' }), dlqList));
    view().querySelectorAll('[data-delete]').forEach(b => b.onclick = () =>
        op(() => api(`/api/dlq/${b.dataset.delete}`, { method: 'DELETE' }), dlqList));
}

const EDITOR_TEMPLATE = `name: my-workflow
trigger:
  webhook: { path: "my-workflow" }
  # or: cron: "0 9 * * *"
  # or: poll: { interval: 5m, http: { url: "https://..." }, fire_when: "changed" }
steps:
  - name: fetch
    action: http
    with:
      method: GET
      url: "https://example.com/api"

  - name: notify
    needs: [fetch]
    if: "{{ steps.fetch.status == 200 }}"
    action: telegram
    with:
      chat_id: "\${TELEGRAM_CHAT_ID}"
      text: "Result: {{ steps.fetch.body }}"
`;

/** Editor: textarea + line numbers + tab handling; 400s render inline. */
export async function editorView(id) {
    const existing = id ? await api(`/api/workflows/${id}`) : null;
    view().innerHTML = `
        <h1>${existing ? `Edit <span class="mono">${esc(existing.name)}</span>` : 'New workflow'}</h1>
        <div class="editor-wrap">
            <pre class="gutter" id="gutter" aria-hidden="true"></pre>
            <textarea id="yaml-editor" spellcheck="false" autocomplete="off"></textarea>
        </div>
        <div id="editor-error" class="editor-error" hidden></div>
        <div class="toolbar">
            <button id="save-btn">${existing ? 'Save (new version)' : 'Create'}</button>
            <button id="preview-btn">Preview ▶ what would happen now</button>
            <a class="btn" href="${existing ? `#/wf/${id}` : '#/'}">Cancel</a>
        </div>
        <div id="preview-result"></div>`;

    const textarea = document.getElementById('yaml-editor');
    const gutter = document.getElementById('gutter');
    const imported = existing ? null : sessionStorage.getItem('potok-import-template');
    if (imported) sessionStorage.removeItem('potok-import-template');
    textarea.value = existing ? existing.yamlSource : (imported ?? EDITOR_TEMPLATE);

    // surface what the user must fill in after importing a template
    const placeholders = [...new Set([
        ...textarea.value.matchAll(/\$\{([A-Z_][A-Z0-9_]*)\}/g),
    ].map(m => m[0]))];
    if (imported && placeholders.length) {
        const note = document.createElement('div');
        note.className = 'flash';
        note.textContent = `Fill in before saving: ${placeholders.join(', ')} ` +
            '(environment variables — set them on the server)';
        view().insertBefore(note, view().children[1]);
    }

    const syncGutter = () => {
        const lines = textarea.value.split('\n').length;
        gutter.textContent = Array.from({ length: lines }, (_, i) => i + 1).join('\n');
    };
    textarea.addEventListener('input', syncGutter);
    textarea.addEventListener('scroll', () => { gutter.scrollTop = textarea.scrollTop; });
    textarea.addEventListener('keydown', e => {
        if (e.key === 'Tab') {
            e.preventDefault();
            const { selectionStart: s, selectionEnd: end } = textarea;
            textarea.setRangeText('  ', s, end, 'end');
            syncGutter();
        }
    });
    syncGutter();

    document.getElementById('preview-btn').onclick = () =>
        runPreview(textarea.value, document.getElementById('preview-result'));

    document.getElementById('save-btn').onclick = async () => {
        const errorBox = document.getElementById('editor-error');
        errorBox.hidden = true;
        try {
            const saved = existing
                ? await apiYaml(`/api/workflows/${id}`, 'PUT', textarea.value)
                : await apiYaml('/api/workflows', 'POST', textarea.value);
            location.hash = `#/wf/${saved.id}`;
        } catch (e) {
            errorBox.textContent = `✗ ${e.message}`;
            errorBox.hidden = false;
        }
    };
}

export async function tokensView() {
    const tokens = await api('/api/tokens');
    const rows = tokens.map(t => `<tr>
        <td>${esc(t.name)}</td>
        <td class="muted">${fmtTime(t.createdAt)}</td>
        <td class="muted">${t.lastUsedAt ? fmtTime(t.lastUsedAt) : 'never'}</td>
        <td>${t.revokedAt
            ? `<span class="status disabled">revoked</span>`
            : `<span class="status enabled">active</span>`}</td>
        <td>${t.revokedAt ? '' : `<button class="danger" data-revoke="${t.id}">Revoke</button>`}</td>
    </tr>`).join('');

    view().innerHTML = `<h1>API tokens</h1>
        <div class="toolbar">
            <input id="token-name" placeholder="token name (e.g. ci-bot)">
            <button id="token-create">Create token</button>
        </div>
        ${tokens.length === 0 ? '<div class="empty">No tokens yet. The POTOK_API_KEY env var is the bootstrap root key.</div>' : `
        <table>
            <thead><tr><th>Name</th><th>Created</th><th>Last used</th><th>State</th><th></th></tr></thead>
            <tbody>${rows}</tbody>
        </table>`}
        <dialog id="token-dialog">
            <h2>Token created</h2>
            <p class="muted">Copy it now — it is shown only once.</p>
            <pre class="yaml" id="token-value"></pre>
            <button id="token-close">Done</button>
        </dialog>`;

    document.getElementById('token-create').onclick = async () => {
        const name = document.getElementById('token-name').value.trim();
        if (!name) { flash('token name is required'); return; }
        try {
            const created = await api('/api/tokens', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name }),
            });
            document.getElementById('token-value').textContent = created.token;
            const dialog = document.getElementById('token-dialog');
            document.getElementById('token-close').onclick = () => { dialog.close(); tokensView(); };
            dialog.showModal();
        } catch (e) {
            flash(e.message);
        }
    };
    view().querySelectorAll('[data-revoke]').forEach(b => b.onclick = () =>
        op(() => api(`/api/tokens/${b.dataset.revoke}`, { method: 'DELETE' }), tokensView));
}

export async function refreshDlqBadge() {
    try {
        const data = await api('/api/dlq?size=1');
        const badge = document.getElementById('dlq-badge');
        badge.hidden = data.total === 0;
        badge.textContent = data.total;
    } catch { /* auth prompt in flight — badge refreshes next tick */ }
}

export async function refreshRecipientsBadge() {
    try {
        const data = await api('/api/recipients?size=1&status=PENDING');
        const badge = document.getElementById('recipients-badge');
        badge.hidden = data.pending === 0;
        badge.textContent = data.pending;
    } catch { /* see DLQ badge */ }
}

/**
 * Recipients page. Routes WHO RECEIVES telegram messages — NOT access. Auth
 * to Potok stays X-API-Key / api_token regardless of who is approved here.
 */
export async function recipientsView(filter = 'ALL') {
    const path = filter === 'ALL' ? '/api/recipients?size=200'
        : `/api/recipients?size=200&status=${filter}`;
    const [data, settings] = await Promise.all([
        api(path),
        api('/api/settings'),
    ]);

    const statusChip = s => `<span class="status ${esc(s)}">${esc(s)}</span>`;
    const rowActions = r => {
        const buttons = [];
        if (r.status !== 'APPROVED') {
            buttons.push(`<button data-approve="${r.id}">${r.status === 'REVOKED' ? 'Re-approve' : 'Approve'}</button>`);
        }
        if (r.status !== 'REVOKED') {
            buttons.push(`<button class="danger" data-revoke="${r.id}">Revoke</button>`);
        }
        buttons.push(`<button class="danger" data-delete="${r.id}">Delete</button>`);
        return buttons.join(' ');
    };

    const rows = data.items.map(r => `<tr>
        <td>${esc(r.displayName)}</td>
        <td class="mono muted">${esc(r.chatIdMasked)}</td>
        <td>${statusChip(r.status)}</td>
        <td class="muted">${fmtTime(r.lastSeenAt)}</td>
        <td class="muted">${fmtTime(r.createdAt)}</td>
        <td>${rowActions(r)}</td>
    </tr>`).join('');

    const filterButton = (label, value) =>
        `<button data-filter="${value}" ${value === filter ? 'disabled' : ''}>${label}</button>`;

    view().innerHTML = `<h1>Recipients</h1>
        <div class="card">
            <p class="muted" style="margin:0 0 0.5rem 0">
                Telegram recipients receive bot notifications.
                <strong>They have no access to Potok</strong> — the API and dashboard always stay behind
                <code>X-API-Key</code>/<code>api_token</code>.
            </p>
            <label style="display:flex; gap:0.5rem; align-items:center; cursor:pointer">
                <input type="checkbox" id="auto-approve" ${settings.telegram_auto_approve ? 'checked' : ''}>
                <span>Auto-approve new people who message the bot
                    <span class="muted">(default off — anyone who finds the bot would otherwise be subscribed automatically)</span>
                </span>
            </label>
        </div>
        <div class="toolbar">
            ${filterButton(`All (${data.pending + data.approved + data.revoked})`, 'ALL')}
            ${filterButton(`Pending (${data.pending})`, 'PENDING')}
            ${filterButton(`Approved (${data.approved})`, 'APPROVED')}
            ${filterButton(`Revoked (${data.revoked})`, 'REVOKED')}
        </div>
        ${data.items.length === 0 ? `<div class="empty">
            ${filter === 'ALL'
                ? 'No recipients yet — message the bot from a Telegram account to register.'
                : `No ${filter.toLowerCase()} recipients.`}
        </div>` : `
        <table>
            <thead><tr>
                <th>Display name</th><th>Chat id</th><th>Status</th>
                <th>Last seen</th><th>Created</th><th></th>
            </tr></thead>
            <tbody>${rows}</tbody>
        </table>`}`;

    document.getElementById('auto-approve').onchange = async e => {
        try {
            await api('/api/settings', {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ telegram_auto_approve: e.target.checked }),
            });
        } catch (err) {
            flash(err.message);
            e.target.checked = !e.target.checked;
        }
    };
    view().querySelectorAll('[data-filter]').forEach(b => b.onclick = () => recipientsView(b.dataset.filter));
    view().querySelectorAll('[data-approve]').forEach(b => b.onclick = () =>
        op(() => api(`/api/recipients/${b.dataset.approve}/approve`, { method: 'POST' }),
            () => recipientsView(filter)));
    view().querySelectorAll('[data-revoke]').forEach(b => b.onclick = () =>
        op(() => api(`/api/recipients/${b.dataset.revoke}/revoke`, { method: 'POST' }),
            () => recipientsView(filter)));
    view().querySelectorAll('[data-delete]').forEach(b => b.onclick = () =>
        op(() => api(`/api/recipients/${b.dataset.delete}`, { method: 'DELETE' }),
            () => recipientsView(filter)));
}
