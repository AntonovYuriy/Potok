// Hash router + auto-refresh (simple polling, 7s on the open view).
import { initAuth } from './api.js';
import { workflowList, workflowDetail, executionDetail, dlqList, refreshDlqBadge } from './views.js';

const REFRESH_MS = 7000;
let refreshTimer = null;

function route() {
    const hash = location.hash || '#/';
    const render = () => {
        let match;
        if ((match = hash.match(/^#\/wf\/([0-9a-f-]+)/))) return workflowDetail(match[1]);
        if ((match = hash.match(/^#\/exec\/([0-9a-f-]+)/))) return executionDetail(match[1]);
        if (hash.startsWith('#/dlq')) return dlqList();
        return workflowList();
    };

    document.querySelectorAll('[data-nav]').forEach(a => a.classList.toggle(
        'active',
        (a.dataset.nav === 'dlq') === hash.startsWith('#/dlq')));

    render().catch(e => {
        document.getElementById('view').innerHTML =
            `<div class="empty">⚠️ ${e.message}</div>`;
    });

    clearInterval(refreshTimer);
    refreshTimer = setInterval(() => {
        render().catch(() => { /* keep last view on transient errors */ });
        refreshDlqBadge();
    }, REFRESH_MS);
}

window.addEventListener('hashchange', route);

await initAuth();
route();
refreshDlqBadge();
