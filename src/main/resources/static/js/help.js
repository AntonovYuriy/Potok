// Help section: example gallery (import into the editor) + YAML reference.
// Data comes from /help/templates.json + /help/reference.json + /help/examples/*
// — the same files that live in examples/ and feed docs/use-cases.md.

const view = () => document.getElementById('view');
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

async function loadTemplates() {
    return fetch('/help/templates.json').then(r => r.json());
}

export async function helpView() {
    const sub = (location.hash.match(/^#\/help\/?(.*)$/) || [])[1] || '';
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
            <p class="muted">What arrives in Telegram:</p>
            <pre class="yaml">${esc(t.sample)}</pre>
            <pre class="yaml">${esc(yaml)}</pre>
            <div class="toolbar">
                <button id="import-btn">Import into editor</button>
                <button id="back-btn">← All examples</button>
            </div>
        </div>`;
    document.getElementById('import-btn').onclick = () => {
        sessionStorage.setItem('potok-import-template', yaml);
        location.hash = '#/new';
    };
    document.getElementById('back-btn').onclick = () => { location.hash = '#/help'; };
}

async function renderReference() {
    const ref = await fetch('/help/reference.json').then(r => r.json());
    const rows = (items, cols) => items.map(i =>
        `<tr>${cols.map(c => `<td>${c === 'syntax' || c === 'params' || c === 'output'
            ? `<code>${esc(i[c])}</code>` : esc(i[c])}</td>`).join('')}</tr>`).join('');
    document.getElementById('help-body').innerHTML = `
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
