// Fetch wrapper: attaches X-API-Key (sessionStorage), re-prompts on 401.

let authRequired = false;

export async function initAuth() {
    const meta = await fetch('/api/meta').then(r => r.json());
    authRequired = meta.authRequired;
    document.getElementById('auth-indicator').hidden = !authRequired;
    if (authRequired && !sessionStorage.getItem('potok-api-key')) {
        await promptForKey();
    }
}

function promptForKey() {
    return new Promise(resolve => {
        const dialog = document.getElementById('key-dialog');
        const form = document.getElementById('key-form');
        const input = document.getElementById('key-input');
        const onSubmit = () => {
            sessionStorage.setItem('potok-api-key', input.value.trim());
            form.removeEventListener('submit', onSubmit);
            resolve();
        };
        form.addEventListener('submit', onSubmit);
        dialog.showModal();
    });
}

export async function api(path, options = {}) {
    const headers = { ...(options.headers || {}) };
    const key = sessionStorage.getItem('potok-api-key');
    if (key) headers['X-API-Key'] = key;

    const response = await fetch(path, { ...options, headers });
    if (response.status === 401) {
        sessionStorage.removeItem('potok-api-key');
        await promptForKey();
        return api(path, options);
    }
    if (response.status === 204) return null;
    const body = await response.json().catch(() => null);
    if (!response.ok) {
        throw new Error(body?.detail || body?.title || `HTTP ${response.status}`);
    }
    return body;
}
