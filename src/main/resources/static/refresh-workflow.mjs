const panel = document.getElementById('refresh-panel');

if (panel) {
    const workflowKey = 'eaukcija.refresh.workflowId';
    const idempotencyKey = 'eaukcija.refresh.idempotencyKey';
    const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    const stages = ['DOWNLOAD_LISTINGS', 'DOWNLOAD_DETAILS', 'PROCESS_LOCATIONS', 'PREPARE_MAP'];
    const statuses = new Set(['IDLE', 'RUNNING', 'SUCCEEDED', 'FAILED']);
    const stageLabels = Object.freeze({
        DOWNLOAD_LISTINGS: 'Преузимање огласа',
        DOWNLOAD_DETAILS: 'Преузимање детаља',
        PROCESS_LOCATIONS: 'Обрада локација',
        PREPARE_MAP: 'Припрема карте',
        COMPLETED: 'Завршено'
    });
    const precisionLabels = Object.freeze({
        PARCEL: 'парцела',
        ADDRESS: 'адреса',
        STREET: 'улица',
        CADASTRAL_MUNICIPALITY: 'центар КО',
        SETTLEMENT: 'центар насеља',
        MUNICIPALITY: 'центар општине',
        NONE: 'без положаја'
    });
    const elements = {
        start: document.getElementById('refresh-start'),
        retry: document.getElementById('refresh-retry'),
        status: document.getElementById('refresh-status'),
        polite: document.getElementById('refresh-polite'),
        alert: document.getElementById('refresh-alert'),
        startedAt: document.getElementById('refresh-started-at'),
        elapsed: document.getElementById('refresh-elapsed'),
        lastSuccess: document.getElementById('refresh-last-success'),
        result: document.getElementById('refresh-result'),
        schedule: document.getElementById('refresh-schedule'),
        advancedStatus: document.getElementById('advanced-status'),
        advancedSource: document.getElementById('advanced-source-sync'),
        advancedEnrichment: document.getElementById('advanced-enrichment')
    };
    let busy = false;
    let currentState = null;
    let firstStateRendered = false;
    let lastAnnouncement = null;
    let pollTimer = null;
    let pollFailures = 0;
    const channel = 'BroadcastChannel' in window
        ? new BroadcastChannel('eaukcija-refresh') : null;

    elements.start.addEventListener('click', startRefresh);
    elements.retry.addEventListener('click', startRefresh);
    elements.advancedSource.addEventListener('click', () => startAdvanced(
        '/api/sync/runs', 'Покренуто је само преузимање изворних података. Карта још није потврђена.'));
    elements.advancedEnrichment.addEventListener('click', () => startAdvanced(
        '/api/enrichment/runs', 'Покренута је само обрада локација. Карта још није потврђена.'));
    channel?.addEventListener('message', event => {
        if (uuidPattern.test(event.data?.workflowId || '')) {
            localStorage.setItem(workflowKey, event.data.workflowId);
            schedulePoll(event.data.workflowId, 0);
        }
    });
    window.setInterval(updateElapsed, 1000);
    restore();

    async function restore() {
        const retained = localStorage.getItem(workflowKey);
        const url = uuidPattern.test(retained || '')
            ? `/api/operator/refresh/${encodeURIComponent(retained)}`
            : '/api/operator/refresh';
        try {
            const response = await fetch(url, {cache: 'no-store', headers: {'Accept': 'application/json'}});
            if (response.status >= 400 && response.status < 500 && retained) {
                localStorage.removeItem(workflowKey);
                sessionStorage.removeItem(idempotencyKey);
                if (response.status === 404) {
                    await restore();
                } else {
                    renderUnavailable();
                }
                return;
            }
            if (!response.ok) {
                throw new Error(`REFRESH_STATUS_${response.status}`);
            }
            const state = await response.json();
            renderState(validateState(state));
            if (state.status === 'RUNNING') {
                localStorage.setItem(workflowKey, state.workflowId);
                schedulePoll(state.workflowId, 1000);
            }
        } catch (_error) {
            renderUnavailable();
            if (uuidPattern.test(retained || '')) {
                schedulePoll(retained, 1000);
            }
        }
    }

    async function startRefresh() {
        if (busy || panel.dataset.refreshEnabled !== 'true') {
            return;
        }
        busy = true;
        setActionBusy(true);
        announce('Покретање потпуног освежавања…', 'progress');
        let key = sessionStorage.getItem(idempotencyKey);
        if (!uuidPattern.test(key || '')) {
            key = crypto.randomUUID();
            sessionStorage.setItem(idempotencyKey, key);
        }
        try {
            const response = await fetch('/api/operator/refresh', {
                method: 'POST',
                headers: {
                    'Accept': 'application/json',
                    'Idempotency-Key': key,
                    'X-Operator-Request': 'refresh-v1'
                }
            });
            const body = await response.json().catch(() => ({}));
            if (!response.ok || !uuidPattern.test(body.workflowId || '')) {
                if (response.status === 400) {
                    sessionStorage.removeItem(idempotencyKey);
                }
                throw new Error(body.code || `REFRESH_START_${response.status}`);
            }
            localStorage.setItem(workflowKey, body.workflowId);
            channel?.postMessage({workflowId: body.workflowId});
            await poll(body.workflowId);
        } catch (error) {
            busy = false;
            setActionBusy(false);
            announce(triggerError(error.message), 'error');
        }
    }

    async function poll(workflowId) {
        window.clearTimeout(pollTimer);
        try {
            const response = await fetch(
                `/api/operator/refresh/${encodeURIComponent(workflowId)}`,
                {cache: 'no-store', headers: {'Accept': 'application/json'}});
            if (!response.ok) {
                throw new Error(`REFRESH_STATUS_${response.status}`);
            }
            const state = validateState(await response.json());
            pollFailures = 0;
            renderState(state);
            if (state.status === 'RUNNING') {
                schedulePoll(workflowId, 1000);
            } else {
                busy = false;
                localStorage.removeItem(workflowKey);
                sessionStorage.removeItem(idempotencyKey);
                setActionBusy(false);
            }
        } catch (_error) {
            pollFailures++;
            announce('Сачувани ток постоји, али његов статус тренутно није доступан. Провера ће се поновити.', 'error');
            schedulePoll(workflowId, Math.min(30000, 1000 * (2 ** Math.min(pollFailures, 5))));
        }
    }

    function schedulePoll(workflowId, delay) {
        window.clearTimeout(pollTimer);
        pollTimer = window.setTimeout(() => poll(workflowId), delay);
    }

    function validateState(state) {
        if (!state || !statuses.has(state.status)
                || (state.workflowId !== null && !uuidPattern.test(state.workflowId || ''))
                || (state.stage !== null && ![...stages, 'COMPLETED'].includes(state.stage))) {
            throw new Error('INVALID_REFRESH_STATE');
        }
        for (const field of [
            'listingsProcessed', 'listingsTotal', 'detailsProcessed', 'detailsTotal',
            'locationsProcessed', 'locationsTotal', 'mappedCount', 'populationCount'
        ]) {
            if (!Number.isSafeInteger(state[field]) || state[field] < 0) {
                throw new Error('INVALID_REFRESH_COUNTS');
            }
        }
        return state;
    }

    function renderState(state) {
        const previous = currentState;
        const focusedAction = document.activeElement === elements.start
                || document.activeElement === elements.retry
            ? document.activeElement : null;
        currentState = state;
        busy = state.status === 'RUNNING';
        if (state.status !== 'RUNNING') {
            sessionStorage.removeItem(idempotencyKey);
        }
        panel.dataset.refreshEnabled = String(state.enabled);
        setActionBusy(busy || !state.enabled);
        renderStages(state);
        elements.startedAt.textContent = formatInstant(state.startedAt, 'Није покренуто');
        elements.lastSuccess.textContent = formatInstant(
            state.lastSuccessfulCompleteRefresh, 'Није забележено');
        renderSchedule(state);
        elements.result.hidden = true;
        elements.retry.hidden = true;
        elements.start.hidden = false;

        if (state.status === 'IDLE') {
            announce(state.enabled
                ? 'Освежавање није покренуто.'
                : 'Потпуно освежавање је паузирано локалном конфигурацијом.', 'idle');
        } else if (state.status === 'RUNNING') {
            localStorage.setItem(workflowKey, state.workflowId);
            announce(`У току: ${stageLabels[state.stage]}.`, 'progress');
        } else if (state.status === 'FAILED') {
            elements.start.hidden = true;
            elements.retry.hidden = false;
            announce(state.failureMessage || 'Освежавање није завршено. Покушајте поново.', 'error');
        } else if (state.status === 'SUCCEEDED') {
            const summary = precisionSummary(state.precisionSummary);
            elements.result.textContent = `Карта је спремна. Приказано је ${state.mappedCount} од ${state.populationCount} аукција.${summary}`;
            elements.result.hidden = false;
            announce(`Карта је спремна. Мапирано је ${state.mappedCount} од ${state.populationCount} аукција.`, 'success');
            if (firstStateRendered && previous?.status === 'RUNNING') {
                refreshCatalogueWithoutReload();
                window.dispatchEvent(new CustomEvent('eaukcija:refresh-complete', {
                    detail: {workflowId: state.workflowId}
                }));
            }
        }
        if (focusedAction?.hidden) {
            const nextAction = elements.retry.hidden ? elements.start : elements.retry;
            nextAction.focus({preventScroll: true});
        }
        updateElapsed();
        firstStateRendered = true;
    }

    function renderStages(state) {
        const currentIndex = stages.indexOf(state.stage);
        for (const [index, stage] of stages.entries()) {
            const item = panel.querySelector(`[data-refresh-stage="${stage}"]`);
            item.removeAttribute('aria-current');
            let itemState = 'pending';
            if (state.status === 'SUCCEEDED' || state.stage === 'COMPLETED' || index < currentIndex) {
                itemState = 'complete';
            } else if (index === currentIndex) {
                itemState = state.status === 'FAILED' ? 'error' : 'active';
                item.setAttribute('aria-current', 'step');
            }
            item.dataset.state = itemState;
            item.querySelector('.refresh-stage-count').textContent = countFor(stage, state);
        }
    }

    function countFor(stage, state) {
        const pair = stage === 'DOWNLOAD_LISTINGS'
            ? [state.listingsProcessed, state.listingsTotal]
            : stage === 'DOWNLOAD_DETAILS'
                ? [state.detailsProcessed, state.detailsTotal]
                : stage === 'PROCESS_LOCATIONS'
                    ? [state.locationsProcessed, state.locationsTotal]
                    : [state.mappedCount, state.populationCount];
        return pair[1] > 0 ? `${pair[0]} / ${pair[1]}` : '';
    }

    function announce(message, kind) {
        const key = `${currentState?.workflowId || 'none'}:${currentState?.status || kind}:${currentState?.stage || ''}:${message}`;
        if (key === lastAnnouncement) {
            return;
        }
        lastAnnouncement = key;
        elements.status.dataset.kind = kind;
        elements.status.textContent = message;
        if (kind === 'error') {
            elements.polite.textContent = '';
            elements.alert.textContent = message;
        } else {
            elements.alert.textContent = '';
            elements.polite.textContent = message;
        }
    }

    function setActionBusy(active) {
        elements.start.setAttribute('aria-disabled', String(active));
        elements.retry.setAttribute('aria-disabled', String(active));
    }

    function updateElapsed() {
        if (!currentState?.startedAt) {
            elements.elapsed.textContent = '—';
            return;
        }
        const started = new Date(currentState.startedAt).getTime();
        const ended = currentState.finishedAt
            ? new Date(currentState.finishedAt).getTime() : Date.now();
        elements.elapsed.textContent = formatDuration(Math.max(0, Math.floor((ended - started) / 1000)));
    }

    function renderSchedule(state) {
        if (!state.scheduleEnabled) {
            elements.schedule.textContent = 'Аутоматско дневно освежавање је паузирано локалном конфигурацијом.';
            return;
        }
        elements.schedule.textContent = `Аутоматско освежавање се покреће једном дневно (${state.scheduleZone}). Следеће покретање: ${formatInstant(state.nextScheduledRun, 'није израчунато')}.`;
    }

    async function refreshCatalogueWithoutReload() {
        try {
            const response = await fetch(window.location.href, {cache: 'no-store', headers: {'Accept': 'text/html'}});
            if (!response.ok) {
                return;
            }
            const fresh = new DOMParser().parseFromString(await response.text(), 'text/html');
            for (const selector of ['header .stats', '.results-info', '.table-scroll', '.pagination']) {
                const current = document.querySelector(selector);
                const replacement = fresh.querySelector(selector);
                if (current && replacement) {
                    current.replaceWith(replacement);
                } else if (current && !replacement && selector === '.pagination') {
                    current.remove();
                } else if (!current && replacement && selector === '.pagination') {
                    document.querySelector('.table-scroll')?.insertAdjacentElement('afterend', replacement);
                }
            }
        } catch (_ignored) {
            // The workflow remains successful; a later filter/map action retries reads.
        }
    }

    async function startAdvanced(url, successMessage) {
        if (elements.advancedSource.getAttribute('aria-disabled') === 'true'
                || elements.advancedEnrichment.getAttribute('aria-disabled') === 'true') {
            return;
        }
        elements.advancedSource.setAttribute('aria-disabled', 'true');
        elements.advancedEnrichment.setAttribute('aria-disabled', 'true');
        elements.advancedStatus.textContent = 'Покретање напредне фазе…';
        try {
            const response = await fetch(url, {
                method: 'POST',
                headers: {'Idempotency-Key': crypto.randomUUID(), 'Accept': 'application/json'}
            });
            if (!response.ok && response.status !== 409) {
                throw new Error(`ADVANCED_${response.status}`);
            }
            elements.advancedStatus.textContent = response.status === 409
                ? 'Друга обрада је већ у току. Ова контрола није покренула дупли посао.'
                : successMessage;
        } catch (_error) {
            elements.advancedStatus.textContent = 'Напредна фаза није покренута. Користите дијагностику система.';
        } finally {
            elements.advancedSource.setAttribute('aria-disabled', 'false');
            elements.advancedEnrichment.setAttribute('aria-disabled', 'false');
        }
    }

    function precisionSummary(counts) {
        const parts = Object.entries(counts || {})
            .filter(([key, value]) => precisionLabels[key] && Number.isSafeInteger(value) && value > 0)
            .map(([key, value]) => `${precisionLabels[key]}: ${value}`);
        return parts.length ? ` Прецизност — ${parts.join(', ')}.` : '';
    }

    function formatInstant(value, fallback) {
        if (!value) {
            return fallback;
        }
        const instant = new Date(value);
        if (Number.isNaN(instant.getTime())) {
            return fallback;
        }
        return new Intl.DateTimeFormat('sr-RS', {
            dateStyle: 'medium', timeStyle: 'short', timeZone: 'Europe/Belgrade'
        }).format(instant);
    }

    function formatDuration(seconds) {
        const hours = Math.floor(seconds / 3600);
        const minutes = Math.floor((seconds % 3600) / 60);
        const remaining = seconds % 60;
        return hours > 0 ? `${hours} ч ${minutes} мин ${remaining} сек`
            : minutes > 0 ? `${minutes} мин ${remaining} сек` : `${remaining} сек`;
    }

    function triggerError(code) {
        if (code === 'INVALID_IDEMPOTENCY_KEY') {
            return 'Захтев није исправан. Покушајте поново.';
        }
        if (code === 'REFRESH_MUTATION_FORBIDDEN') {
            return 'Освежавање се може покренути само са локалне странице.';
        }
        return 'Освежавање тренутно није покренуто. Покушајте поново.';
    }

    function renderUnavailable() {
        currentState = null;
        setActionBusy(false);
        elements.start.setAttribute('aria-disabled', panel.dataset.refreshEnabled !== 'true' ? 'true' : 'false');
        announce(panel.dataset.refreshEnabled === 'true'
            ? 'Статус освежавања тренутно није доступан.'
            : 'Потпуно освежавање није доступно у овом профилу.', 'error');
        elements.schedule.textContent = 'Распоред тренутно није доступан.';
    }
}
