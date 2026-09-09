// Presentation only. Applied criteria, drafts, camera and result state remain
// owned by the single shared view; switching modes never navigates or submits.
const workspace = document.getElementById('workspace');
if (workspace) {
    const toolbar = workspace.querySelector('.workspace-toolbar');
    const modes = [...toolbar.querySelectorAll('[data-workspace-mode]')];
    const railToggle = document.getElementById('workspace-rail-toggle');
    const filterToggle = document.getElementById('workspace-filter-toggle');
    const form = document.getElementById('shared-filters');

    function setMode(mode) {
        if (!['results', 'map', 'table'].includes(mode)) return;
        workspace.dataset.mode = mode;
        if (mode !== 'table') workspace.dataset.rail = String(mode === 'results');
        for (const button of modes) {
            button.setAttribute('aria-pressed', String(button.dataset.workspaceMode === mode));
        }
        railToggle.setAttribute('aria-expanded', String(mode === 'results'));
    }

    modes.forEach(button => button.addEventListener('click', () => setMode(button.dataset.workspaceMode)));
    railToggle.addEventListener('click', () => setMode(workspace.dataset.mode === 'results' ? 'map' : 'results'));
    filterToggle.addEventListener('click', () => {
        form.hidden = !form.hidden;
        filterToggle.setAttribute('aria-expanded', String(!form.hidden));
    });
    window.addEventListener('eaukcija:show-map', () => setMode('results'));

    // Escape closes explanatory disclosures without losing the keyboard's place.
    for (const id of ['map-reference', 'refresh-details']) {
        const details = document.getElementById(id);
        details.addEventListener('keydown', event => {
            if (event.key !== 'Escape' || !details.open) return;
            event.preventDefault();
            details.open = false;
            details.querySelector('summary').focus({preventScroll: true});
        });
    }
    setMode('results');
    toolbar.hidden = false;
    document.documentElement.classList.add('workspace-enhanced');
}
