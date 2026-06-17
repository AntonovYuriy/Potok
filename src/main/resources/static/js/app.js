// Hash router + auto-refresh (simple polling, 7s on the open view).
// Editor views never auto-refresh — that would eat unsaved typing.
import { initAuth } from './api.js';
import { workflowList, workflowDetail, executionDetail, dlqList, refreshDlqBadge, editorView, tokensView, recipientsView, refreshRecipientsBadge } from './views.js';
import { helpView } from './help.js';

const REFRESH_MS = 7000;
let refreshTimer = null;

function resolve(hash) {
    let match;
    if (hash === '#/new') return { render: () => editorView(null), refresh: false, nav: 'workflows' };
    if ((match = hash.match(/^#\/wf\/([0-9a-f-]+)\/edit/)))
        return { render: () => editorView(match[1]), refresh: false, nav: 'workflows' };
    if ((match = hash.match(/^#\/wf\/([0-9a-f-]+)/)))
        return { render: () => workflowDetail(match[1]), refresh: true, nav: 'workflows' };
    if ((match = hash.match(/^#\/exec\/([0-9a-f-]+)/)))
        return { render: () => executionDetail(match[1]), refresh: true, nav: 'workflows' };
    if (hash.startsWith('#/dlq')) return { render: dlqList, refresh: true, nav: 'dlq' };
    if (hash.startsWith('#/recipients')) return { render: recipientsView, refresh: true, nav: 'recipients' };
    if (hash.startsWith('#/tokens')) return { render: tokensView, refresh: false, nav: 'tokens' };
    if (hash.startsWith('#/help')) return { render: helpView, refresh: false, nav: 'help' };
    return { render: workflowList, refresh: true, nav: 'workflows' };
}

function route() {
    const target = resolve(location.hash || '#/');

    document.querySelectorAll('[data-nav]').forEach(a =>
        a.classList.toggle('active', a.dataset.nav === target.nav));

    target.render().catch(e => {
        document.getElementById('view').innerHTML =
            `<div class="empty">⚠️ ${e.message}</div>`;
    });

    clearInterval(refreshTimer);
    if (target.refresh) {
        refreshTimer = setInterval(() => {
            target.render().catch(() => { /* keep last view on transient errors */ });
            refreshDlqBadge();
            refreshRecipientsBadge();
        }, REFRESH_MS);
    }
}

window.addEventListener('hashchange', route);

await initAuth();
route();
refreshDlqBadge();
refreshRecipientsBadge();
