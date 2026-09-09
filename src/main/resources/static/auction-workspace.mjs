// Presentation only: the shared view still owns applied criteria, drafts and selection.
const workspace = document.getElementById('workspace');
if (workspace) {
    const toolbar = workspace.querySelector('.workspace-toolbar');
    const modes = [...toolbar.querySelectorAll('[data-workspace-mode]')];
    const railToggle = document.getElementById('workspace-rail-toggle');
    const filterToggle = document.getElementById('workspace-filter-toggle');
    const form = document.getElementById('shared-filters');
    const closeFilters = document.getElementById('filter-panel-close');
    const preferenceKey = 'eaukcija.workspace.v1.';
    // Storage can be unavailable (private/locked-down browsers); enhancement still works.
    const remembered = name => {
        try { return localStorage.getItem(preferenceKey + name) === 'true'; }
        catch (_error) { return false; }
    };
    const remember = (name, value) => {
        try { localStorage.setItem(preferenceKey + name, String(value)); }
        catch (_error) { /* Presentation persistence is optional. */ }
    };

    function setMode(mode) {
        if (!['results', 'map', 'table'].includes(mode)) return;
        workspace.dataset.mode = mode;
        if (mode !== 'table') workspace.dataset.rail = String(mode === 'results');
        for (const button of modes) button.setAttribute('aria-pressed', String(button.dataset.workspaceMode === mode));
        railToggle.setAttribute('aria-expanded', String(mode === 'results'));
        const selection = document.getElementById('selection-toggle');
        const selectionHost = document.querySelector(mode === 'table' ? '.auction-map-heading-row' : '.map-canvas-frame');
        if (selection.parentElement !== selectionHost) selectionHost.append(selection);
        window.dispatchEvent(new CustomEvent('eaukcija:workspace-mode', {detail: {mode}}));
    }

    function setFilters(open, {focus = false, persist = true} = {}) {
        form.hidden = !open;
        workspace.dataset.filters = String(open);
        filterToggle.setAttribute('aria-expanded', String(open));
        if (persist) remember('filters', open);
        if (focus) (open ? form.querySelector('input[type=search]') : filterToggle).focus({preventScroll: true});
    }

    modes.forEach(button => button.addEventListener('click', () => setMode(button.dataset.workspaceMode)));
    railToggle.addEventListener('click', () => setMode(workspace.dataset.mode === 'results' ? 'map' : 'results'));
    filterToggle.addEventListener('click', () => setFilters(form.hidden, {focus: true}));
    closeFilters.addEventListener('click', () => setFilters(false, {focus: true}));
    form.addEventListener('keydown', event => {
        if (event.key !== 'Escape' || event.defaultPrevented || event.isComposing) return;
        event.preventDefault();
        setFilters(false, {focus: true});
    });
    // Native validation happens before submit, including controls in closed disclosures.
    const revealField = control => {
        setFilters(true);
        for (let parent = control.parentElement; parent && parent !== form; parent = parent.parentElement) {
            if (parent.matches('details')) parent.open = true;
        }
    };
    form.addEventListener('invalid', event => revealField(event.target), true);
    window.addEventListener('eaukcija:reveal-filter', event => {
        const control = form.elements.namedItem(event.detail.field);
        if (!(control instanceof HTMLElement)) return;
        revealField(control);
        control.focus();
    });
    window.addEventListener('eaukcija:show-map', () => setMode('results'));
    document.getElementById('map-count-table').addEventListener('click', () => {
        document.getElementById('map-counts').open = false;
        setMode('table');
        document.querySelector('.table-scroll').focus({preventScroll: true});
    });

    for (const id of ['map-reference', 'map-counts', 'refresh-details', 'advanced-filters', 'filter-help']) {
        const details = document.getElementById(id);
        details.open = remembered(id);
        details.addEventListener('toggle', () => remember(id, details.open));
        details.addEventListener('keydown', event => {
            if (event.key !== 'Escape' || event.defaultPrevented || !details.open) return;
            event.preventDefault();
            details.open = false;
            details.querySelector('summary').focus({preventScroll: true});
        });
        if (['map-reference', 'map-counts', 'refresh-details'].includes(id)) {
            document.addEventListener('click', event => {
                if (!details.open || details.contains(event.target)) return;
                details.open = false;
            });
        }
    }
    // Reparent once, never clone/recreate: the side form shares the view's height,
    // while counts/warnings retain their full width when the panel opens.
    document.getElementById('workspace-views').prepend(form);
    setMode('results');
    setFilters(remembered('filters'), {persist: false});
    toolbar.hidden = false;
    closeFilters.hidden = false;
    document.documentElement.classList.add('workspace-enhanced');
}
