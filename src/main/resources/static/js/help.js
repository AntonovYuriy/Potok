// Help section: template gallery with FORM-based import + YAML reference.
// Data: /help/templates.json (cards + param schemas), /help/templates/*.yaml.tpl
// (parameterized sources), /help/examples/* (rendered previews) — all generated
// from the same templates/ directory that feeds examples/ and the docs.
import { apiYaml } from './api.js';
import { runPreview } from './preview.js';

const view = () => document.getElementById('view');
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

// {{param.key}} substitution — the param namespace only; runtime {{ steps.* }} untouched
function renderTemplate(tpl, values) {
    return tpl.replace(/\{\{param\.([A-Za-z0-9_]+)}}/g, (_, key) => values[key] ?? '');
}

const VALIDATORS = {
    url: v => /^https?:\/\/.+/.test(v) || 'must start with http(s)://',
    duration: v => /^\d+(s|m|h)$/.test(v) || 'use forms like 30s, 10m, 1h',
    cron: v => v.trim().split(/\s+/).length === 5 || 'needs 5 space-separated fields',
    number: v => /^-?\d+(\.\d+)?$/.test(v) || 'must be a number',
    string: v => v.trim().length > 0 || 'cannot be empty',
    text: v => v.trim().length > 0 || 'cannot be empty',
};

function validateParam(param, value) {
    if (!value || !value.trim()) {
        return param.required ? 'required' : null;
    }
    if (param.type === 'select') {
        return param.options.includes(value.trim()) ? null : `must be one of: ${param.options.join(' ')}`;
    }
    const check = VALIDATORS[param.type];
    if (!check) return null;
    const result = check(value.trim());
    return result === true ? null : result;
}

async function loadTemplates() {
    return fetch('/help/templates.json').then(r => r.json());
}

export async function helpView() {
    const sub = (location.hash.match(/^#\/help\/?(.*)$/) || [])[1] || '';
    if (sub.endsWith('/use')) {
        await renderShell('examples');
        return useForm(sub.slice(0, -'/use'.length));
    }
    if (sub && sub !== 'reference') {
        await renderShell('examples');
        return caseDetail(sub);
    }
    return renderShell(sub === 'reference' ? 'reference' : 'examples');
}

async function renderShell(tab) {
    view().innerHTML = `
        <h1>Help</h1>
        <div class="toolbar">
            <button id="tab-examples" class="${tab === 'examples' ? 'tab-active' : ''}">Examples</button>
            <button id="tab-reference" class="${tab === 'reference' ? 'tab-active' : ''}">YAML reference</button>
        </div>
        <div id="help-body"></div>`;
    document.getElementById('tab-examples').onclick = () => { location.hash = '#/help'; };
    document.getElementById('tab-reference').onclick = () => { location.hash = '#/help/reference'; };
    if (tab === 'examples') {
        await renderExamples();
    } else {
        await renderReference();
    }
}

async function renderExamples() {
    const templates = await loadTemplates();
    document.getElementById('help-body').innerHTML = `
        <div class="cards">${templates.map(t => `
            <div class="case-card" data-case="${t.id}">
                <h3>${esc(t.title)}</h3>
                <p class="muted">${esc(t.problem)}</p>
                <div>
                    <span class="status RUNNING">${esc(t.trigger)}</span>
                    ${t.actions.map(a => `<span class="status SKIPPED">${esc(a)}</span>`).join(' ')}
                </div>
            </div>`).join('')}
        </div>`;
    document.querySelectorAll('[data-case]').forEach(card =>
        card.onclick = () => { location.hash = '#/help/' + card.dataset.case; });
}

async function caseDetail(id) {
    const templates = await loadTemplates();
    const t = templates.find(x => x.id === id);
    const yaml = await fetch(`/help/examples/${t.file}`).then(r => r.text());
    document.getElementById('help-body').innerHTML = `
        <div class="card">
            <h2>${esc(t.title)}</h2>
            <p>${esc(t.problem)}</p>
            <p class="muted">What you'll need: ${esc(
                t.params.filter(p => p.required && p.type !== 'env')
                    .map(p => p.label.toLowerCase()).join(', ') || 'nothing — just a name')}.</p>
            <p class="muted">What arrives in Telegram:</p>
            <pre class="yaml">${esc(t.sample)}</pre>
            <details><summary class="muted">YAML preview (defaults)</summary>
                <pre class="yaml">${esc(yaml)}</pre></details>
            <div class="toolbar">
                <button id="use-btn">Use template</button>
                <button id="back-btn">← All examples</button>
            </div>
        </div>`;
    document.getElementById('use-btn').onclick = () => { location.hash = `#/help/${id}/use`; };
    document.getElementById('back-btn').onclick = () => { location.hash = '#/help'; };
}

/** The form: one typed field per param; submit renders YAML and creates the workflow. */
async function useForm(id) {
    const templates = await loadTemplates();
    const t = templates.find(x => x.id === id);
    const tpl = await fetch(`/help/templates/${id}.yaml.tpl`).then(r => r.text());
    const editable = t.params.filter(p => p.type !== 'env');
    const envNotes = t.params.filter(p => p.type === 'env');

    const inputType = p => p.type === 'url' ? 'url' : p.type === 'number' ? 'number' : 'text';
    const field = p => `
        <label class="form-field" data-key="${p.key}">
            <span>${esc(p.label)}${p.required ? ' <em class="req">*</em>' : ''}</span>
            ${p.type === 'text'
                ? `<textarea id="param-${p.key}" rows="2" placeholder="${esc(p.example ?? '')}">${esc(p.default ?? '')}</textarea>`
                : p.type === 'select'
                ? `<select id="param-${p.key}">${p.options.map(o =>
                        `<option${o === p.default ? ' selected' : ''}>${esc(o)}</option>`).join('')}</select>`
                : `<input id="param-${p.key}" type="${inputType(p)}" step="any"
                       placeholder="${esc(p.example ?? '')}" value="${esc(p.default ?? '')}">`}
            <small class="muted">${esc(p.help ?? '')}</small>
            <small class="field-error" hidden></small>
        </label>`;

    document.getElementById('help-body').innerHTML = `
        <div class="card form-card">
            <h2>${esc(t.title)}</h2>
            <label class="form-field" data-key="name">
                <span>Workflow name <em class="req">*</em></span>
                <input id="param-name" type="text" value="${esc(t.default_name)}">
                <small class="muted">Unique among active workflows</small>
                <small class="field-error" hidden></small>
            </label>
            ${editable.map(field).join('')}
            ${envNotes.map(p => `
                <div class="env-note">🔒 ${esc(p.label)}: ${esc(p.help)}</div>`).join('')}
            <div id="form-error" class="editor-error" hidden></div>
            <div class="toolbar">
                <button id="create-btn">Create workflow</button>
                <button id="preview-btn">Preview ▶ what would happen now</button>
                <a class="btn" href="#" id="advanced-link">Advanced: edit YAML</a>
                <a class="btn" href="#/help/${id}">Cancel</a>
            </div>
        </div>
        <div id="preview-result"></div>`;

    const collect = () => {
        const values = { name: document.getElementById('param-name').value.trim() };
        editable.forEach(p => { values[p.key] = document.getElementById(`param-${p.key}`).value.trim(); });
        return values;
    };

    const validate = values => {
        let firstError = null;
        const nameParam = { key: 'name', type: 'string', required: true };
        for (const p of [nameParam, ...editable]) {
            const error = validateParam(p, values[p.key]);
            const box = document.querySelector(`[data-key="${p.key}"] .field-error`);
            box.textContent = error ?? '';
            box.hidden = !error;
            if (error && !firstError) firstError = p.key;
        }
        return !firstError;
    };

    document.getElementById('create-btn').onclick = async () => {
        const values = collect();
        if (!validate(values)) return;
        const errorBox = document.getElementById('form-error');
        errorBox.hidden = true;
        try {
            const saved = await apiYaml('/api/workflows', 'POST', renderTemplate(tpl, values));
            location.hash = `#/wf/${saved.id}`;
        } catch (e) {
            errorBox.textContent = `✗ ${e.message}`;   // 409 name conflict, 400 validation — API is the authority
            errorBox.hidden = false;
        }
    };
    // dry run with the form's current values — results inline, nothing saved or sent
    document.getElementById('preview-btn').onclick = () => {
        const values = collect();
        if (!validate(values)) return;
        runPreview(renderTemplate(tpl, values), document.getElementById('preview-result'));
    };
    // escape hatch: same values, but through the full editor (today's M5 flow)
    document.getElementById('advanced-link').onclick = e => {
        e.preventDefault();
        sessionStorage.setItem('potok-import-template', renderTemplate(tpl, collect()));
        location.hash = '#/new';
    };
}

async function renderReference() {
    const ref = await fetch('/help/reference.json').then(r => r.json());
    const rows = (items, cols) => items.map(i =>
        `<tr>${cols.map(c => `<td>${c === 'syntax' || c === 'params' || c === 'output'
            ? `<code>${esc(i[c])}</code>` : esc(i[c])}</td>`).join('')}</tr>`).join('');
    document.getElementById('help-body').innerHTML = `
        <p class="muted">Templates are forms now: pick one in Examples, fill the fields, done — YAML is generated for you.
        Preview ▶ runs the workflow once in dry-run mode: read-only fetches happen for real, messages are shown but not sent, nothing is saved.</p>
        <div class="card"><h2>Triggers</h2>
            <table><thead><tr><th>Type</th><th>Syntax</th><th>Notes</th></tr></thead>
            <tbody>${rows(ref.triggers, ['name', 'syntax', 'notes'])}</tbody></table></div>
        <div class="card"><h2>Step fields</h2>
            <table><thead><tr><th>Field</th><th>Notes</th></tr></thead>
            <tbody>${rows(ref.stepFields, ['field', 'notes'])}</tbody></table></div>
        <div class="card"><h2>Conditions</h2>
            <pre class="yaml">${esc(ref.conditions.grammar)}</pre>
            <p>Operators: <code>${esc(ref.conditions.operators)}</code></p>
            <p>Functions: ${esc(ref.conditions.functions)}</p>
            <p class="muted">${esc(ref.conditions.validated)}</p></div>
        <div class="card"><h2>Templating</h2>
            <table><thead><tr><th>Syntax</th><th>Notes</th></tr></thead>
            <tbody>${rows(ref.templating, ['syntax', 'notes'])}</tbody></table></div>
        <div class="card"><h2>Actions</h2>
            <table><thead><tr><th>Action</th><th>Params</th><th>Output</th></tr></thead>
            <tbody>${rows(ref.actions, ['name', 'params', 'output'])}</tbody></table></div>`;
}
