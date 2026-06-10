// View renderers. Each returns nothing; they write into #view.
import { api } from './api.js';

const view = () => document.getElementById('view');
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
        return `<tr>
            <td><a href="#/wf/${w.id}">${esc(w.name)}</a></td>
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
        ${workflows.length === 0 ? '<div class="empty">No workflows yet — POST YAML to /api/workflows</div>' : `
        <table>
            <thead><tr><th>Name</th><th>Trigger</th><th>State</th><th>Last run</th><th></th></tr></thead>
            <tbody>${rows}</tbody>
        </table>`}`;

    view().querySelectorAll('[data-run]').forEach(b => b.onclick = async () => {
        await api(`/api/workflows/${b.dataset.run}/run`, { method: 'POST' });
        workflowList();
    });
    view().querySelectorAll('[data-disable]').forEach(b => b.onclick = async () => {
        await api(`/api/workflows/${b.dataset.disable}`, { method: 'DELETE' });
        workflowList();
    });
    view().querySelectorAll('[data-enable]').forEach(b => b.onclick = async () => {
        await api(`/api/workflows/${b.dataset.enable}/enable`, { method: 'POST' });
        workflowList();
    });
}

export async function workflowDetail(id, page = 0) {
    const [workflow, executions] = await Promise.all([
        api(`/api/workflows/${id}`),
        api(`/api/executions?workflowId=${id}&page=${page}&size=20`),
    ]);

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
            <span class="muted">${triggerKind(workflow.definition)}</span>
        </div>
        <div class="card"><h2 class="muted">Definition</h2><pre class="yaml">${esc(workflow.yamlSource)}</pre></div>
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

    document.getElementById('run-btn').onclick = async () => {
        const r = await api(`/api/workflows/${id}/run`, { method: 'POST' });
        location.hash = `#/exec/${r.executionId}`;
    };
    document.getElementById('prev').onclick = () => workflowDetail(id, page - 1);
    document.getElementById('next').onclick = () => workflowDetail(id, page + 1);
}

export async function executionDetail(id) {
    const execution = await api(`/api/executions/${id}`);
    const steps = (execution.steps ?? []).map(s => `
        <div class="step ${esc(s.status)}">
            <div>
                <div class="mono">${esc(s.name)}</div>
                ${statusBadge(s.status)}
            </div>
            <div>
                <span class="muted">attempt ${s.attempt} · ${duration(s.startedAt, s.finishedAt) || '—'}</span>
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

    view().querySelectorAll('[data-requeue]').forEach(b => b.onclick = async () => {
        await api(`/api/dlq/${b.dataset.requeue}/requeue`, { method: 'POST' });
        dlqList();
    });
    view().querySelectorAll('[data-delete]').forEach(b => b.onclick = async () => {
        await api(`/api/dlq/${b.dataset.delete}`, { method: 'DELETE' });
        dlqList();
    });
}

export async function refreshDlqBadge() {
    try {
        const data = await api('/api/dlq?size=1');
        const badge = document.getElementById('dlq-badge');
        badge.hidden = data.total === 0;
        badge.textContent = data.total;
    } catch { /* auth prompt in flight — badge refreshes next tick */ }
}
