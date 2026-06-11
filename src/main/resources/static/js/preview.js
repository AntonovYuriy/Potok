// Shared "what would happen right now" panel: used by the template form and
// the YAML editor. POSTs the YAML to /api/preview and renders one friendly
// card per step; technical detail stays collapsed.
import { api } from './api.js';

const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

const ICONS = {
    executed: '✓',
    simulated: '✉',
    skipped: '○',
    failed: '✗',
};

function icon(step) {
    if (step.mode === 'simulated' && step.kind === 'http') return '→';
    return ICONS[step.mode] ?? '•';
}

function stepCard(step) {
    return `
        <div class="pv-card pv-${step.mode}">
            <span class="pv-icon">${icon(step)}</span>
            <div>
                <div><span class="mono muted">${esc(step.name)}</span> · ${esc(step.human_summary)}</div>
                ${step.detail ? `<details><summary class="muted">Technical detail</summary>
                    <pre class="yaml">${esc(step.detail)}</pre></details>` : ''}
                ${step.rendered_output ? `<details><summary class="muted">Output</summary>
                    <pre class="yaml">${esc(JSON.stringify(step.rendered_output, null, 2))}</pre></details>` : ''}
            </div>
        </div>`;
}

function triggerCard(t) {
    return `
        <div class="pv-card pv-trigger">
            <span class="pv-icon">⏱</span>
            <div>
                <div><span class="mono muted">trigger: ${esc(t.kind)}</span>${t.human_summary ? ' · ' + esc(t.human_summary) : ''}</div>
                <div class="pv-note">${esc(t.note)}</div>
                ${t.detail ? `<details><summary class="muted">Technical detail</summary>
                    <pre class="yaml">${esc(t.detail)}</pre></details>` : ''}
            </div>
        </div>`;
}

/** Runs the preview and renders the result (or a friendly error) into container. */
export async function runPreview(yaml, container) {
    container.innerHTML = '<div class="muted">Previewing… (read-only checks run for real, nothing is sent)</div>';
    try {
        const result = await api('/api/preview', {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' },
            body: yaml,
        });
        container.innerHTML = `
            <div class="preview-panel">
                <h3>What would happen right now</h3>
                ${triggerCard(result.trigger)}
                ${result.steps.map(stepCard).join('')}
            </div>`;
    } catch (e) {
        container.innerHTML = `<div class="editor-error">✗ ${esc(e.message)}</div>`;
    }
}
