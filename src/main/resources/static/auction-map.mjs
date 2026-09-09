import {NavigationControl, Popup} from './vendor/maplibre-gl/6.1.0/maplibre-gl.mjs';
import {createLocalBasemap} from './basemap-map.mjs';
import {createMunicipalitySelect} from './municipality-select.mjs';
import {createFilterPresentation} from './auction-filter-presentation.mjs';

const POINT_SOURCE = 'auction-points';
const AREA_SOURCE = 'auction-areas';
const SELECTION_SOURCE = 'auction-selection';
const NEIGHBOURHOOD_ZOOM = 13;
const CLOSE_ZOOM = 17;
const HIT_RADIUS_PX = 12;
const CLUSTER_LAYER = 'auction-clusters';
const CLUSTER_COUNT_LAYER = 'auction-cluster-count';
const SELECTED_POINT_LAYER = 'auction-selected-point';
const SELECTED_AREA_LAYER = 'auction-selected-area';
const BASEMAP_SOURCE = 'serbia';
const LOAD_DEBOUNCE_MS = 250;
const RESULT_LIMIT = 1000;
const MAX_BBOX_AREA_SQUARE_KM = 1_000_000;
const REQUEST_AREA_SAFETY_FACTOR = .98;
const EARTH_RADIUS_KM = 6_371.0088;
const EMPTY_COLLECTION = Object.freeze({type: 'FeatureCollection', features: []});
const RSD_AMOUNT_FORMATTER = createRsdAmountFormatter();

const PRECISIONS = Object.freeze({
    PARCEL: {
        explanation: 'Проверена граница или тачка парцеле. Ознака је на парцели; граница парцеле није нужно обрис објекта који се продаје.',
        color: '#7b2cbf',
        shape: 'diamond',
        dash: [10, 1]
    },
    ADDRESS: {
        explanation: 'Адресна тачка; не мора представљати обрис објекта или парцеле.',
        color: '#006d77',
        shape: 'square',
        dash: [3, 1]
    },
    STREET: {
        explanation: 'Приближна тачка улице, без тврдње о тачној адреси.',
        color: '#b45309',
        shape: 'triangle',
        dash: [1, 1]
    },
    CADASTRAL_MUNICIPALITY: {
        explanation: 'Центар катастарске општине; ово није адреса ни парцела.',
        color: '#0057b8',
        shape: 'hexagon',
        dash: [4, 2]
    },
    SETTLEMENT: {
        explanation: 'Центар насеља; стварна непокретност може бити удаљена.',
        color: '#9c2f58',
        shape: 'circle',
        dash: [2, 2]
    },
    MUNICIPALITY: {
        explanation: 'Најшири приближни приказ, у центру општине.',
        color: '#4b5563',
        shape: 'cross',
        dash: [1, 2]
    }
});

const FILTER_FIELDS = Object.freeze(['municipality', 'placeName', 'category', 'status',
    'minPrice', 'maxPrice', 'firstSale', 'search', 'precision', 'from', 'to', 'timeScope']);

const elements = {
    filterForm: document.getElementById('shared-filters'),
    filterReset: document.getElementById('shared-filter-reset'),
    state: document.getElementById('map-state'),
    limitWarning: document.getElementById('map-limit-warning'),
    freshnessWarning: document.getElementById('map-freshness-warning'),
    basemapVersion: document.getElementById('basemap-version'),
    dataVersion: document.getElementById('map-data-version'),
    lastSync: document.getElementById('map-last-sync'),
    resultCount: document.getElementById('map-result-count'),
    resultList: document.getElementById('map-result-list'),
    selection: document.getElementById('map-selection')
};

const municipalitySelect = createMunicipalitySelect(document.getElementById('municipality-filter'));

const FILTER_OPTIONS = Object.freeze({
    precision: selectOptionValues('map-precision-filter')
});

const diagnostics = {
    requestsStarted: 0,
    requestsCompleted: 0,
    requestsAborted: 0,
    lastFeatureCount: 0,
    truncated: false,
    lastState: 'loading',
    lastError: null,
    precisionStyles: Object.keys(PRECISIONS),
    selectedAuctionId: null,
    pendingRefresh: false,
    requestableMinZoom: null,
    lastRequestAreaSquareKm: null,
    mapErrors: 0,
    basemapErrors: 0,
    auctionSourceErrors: 0,
    activeResourceWarnings: 0,
    lastClusterError: null
};

const state = {
    map: null,
    popup: null,
    features: [],
    resultScrollTop: 0,
    selectedAuctionId: readSelectedAuction(),
    // Selection is durable; details visibility and its return-focus trigger are not URL state.
    selectedFeatureId: null,
    clusterSequence: 0,
    detailsOpen: false,
    // URL restoration may show a selection-only summary; dismissal hides that too.
    detailsDismissed: false,
    detailsTrigger: null,
    popupView: null,
    summaryView: null,
    debounceTimer: null,
    activeRequest: null,
    requestSequence: 0,
    metadataWarnings: new Set(),
    metadataSequence: 0,
    resourceWarnings: new Map(),
    pendingRefresh: false,
    quietRefresh: false,
    sourcesReady: false,
    initializationPromise: null,
    resizeObserver: null,
    appliedQuery: new URLSearchParams(elements.filterForm.dataset.query),
    selectionStatus: null,
    lastUsableQuery: new URLSearchParams(elements.filterForm.dataset.query),
    invalidFilter: false
};

const publicApi = {
    ready: false,
    map: null,
    refreshNow: () => refreshNow(),
    getDiagnostics: () => ({...diagnostics, requestInFlight: !!state.activeRequest,
        selectedFeatureId: state.selectedFeatureId, detailsOpen: state.detailsOpen}),
    renderedClusterCount: () => renderedClusterCount(),
    showCluster: cluster => showCluster(cluster),
    waitForMapLoad: (map, timeoutMs) => mapLoaded(map, {
        timeoutMs,
        onRecoverableError: handleMapError
    })
};
if (document.querySelector('.auction-map-panel')?.dataset.mapTestHooks === 'true') {
    window.__auctionMap = publicApi;
}

const filterPresentation = createFilterPresentation({
    form: elements.filterForm, fields: FILTER_FIELDS, appliedQuery: () => state.lastUsableQuery,
    removeCriterion: (name, value) => {
        const query = new URLSearchParams(state.lastUsableQuery);
        if (name === 'municipality') {
            query.delete(name, value);
            // Remove this applied choice only; preserve other municipality drafts.
            municipalitySelect.setValues(new FormData(elements.filterForm).getAll(name).filter(item => item !== value));
        } else {
            query.delete(name);
            if (name === 'timeScope') query.set(name, 'not-ended');
            elements.filterForm.elements.namedItem(name).value = query.get(name) || '';
        }
        query.set('page', '0');
        navigateFilters(query, false);
    },
    reset: () => elements.filterReset.click()
});
replaceUrl(state.appliedQuery);
bindFilterControls();
bindDetailsDismissal();
const metadataPromise = loadMetadata();
initialize();
window.addEventListener('eaukcija:refresh-complete', () => {
    state.metadataWarnings.clear();
    loadMetadata();
    refreshNow();
});

// Private POC refinement commits shapes incrementally. Refresh the local
// viewport while visible/idle; never contact RGZ from the browser or interrupt
// a pending request, camera gesture, or filter debounce. Other profiles opt out.
const autoRefreshMs = Number(document.querySelector('.auction-map-panel')?.dataset.autoRefreshIntervalMs);
if (Number.isFinite(autoRefreshMs) && autoRefreshMs >= 1000 && autoRefreshMs <= 300000) {
    window.setInterval(() => {
        if (!document.hidden && state.sourcesReady && state.map && !state.map.isMoving()
                && !state.activeRequest && !state.debounceTimer && !state.pendingRefresh && !state.invalidFilter) {
            requestRefresh({quiet: true});
            replayPendingRefresh();
        }
    }, autoRefreshMs);
}

async function initialize() {
    if (state.initializationPromise) {
        return state.initializationPromise;
    }
    publicApi.ready = false;
    const attempt = initializeMap();
    state.initializationPromise = attempt;
    try {
        await attempt;
    } finally {
        await metadataPromise;
        publicApi.ready = true;
        if (state.initializationPromise === attempt) {
            state.initializationPromise = null;
        }
    }
}

async function initializeMap() {
    try {
        assertPrecisionContract();
    } catch (error) {
        failMapInitialization(
                error,
                'Дефиниције прецизности карте нису усклађене. Ово је грешка верзије апликације; обратите се одржаваоцу.');
        return;
    }

    try {
        const map = await createLocalBasemap({
            container: 'auction-map',
            center: [initialMapNumber('initialLongitude', 18, 24, 20.46),
                initialMapNumber('initialLatitude', 41, 47, 44.79)],
            zoom: initialMapNumber('initialZoom', 0, 20, 14),
            minZoom: 0,
            maxZoom: 20,
            bearing: 0,
            pitch: 0,
            maxPitch: 0,
            dragRotate: false,
            pitchWithRotate: false,
            touchPitch: false,
            // One resize owner: MapLibre 6 also has a throttled container observer.
            trackResize: false,
            fadeDuration: reducedMotion() ? 0 : 150,
            locale: {'Popup.Close': 'Затвори детаље аукције'}
        });
        configureTwoDimensionalCamera(map);
        state.map = map;
        publicApi.map = map;
        map.addControl(new NavigationControl({showCompass: false}), 'top-right');
        map.on('sourcedata', handleSourceRecovery);
        await mapLoaded(map, {onRecoverableError: handleMapError});
        map.on('error', handleMapError);
        configureAccessibleMap(map);
        map.on('styledata', replayPendingRefresh);
        map.on('idle', replayPendingRefresh);
        addAuctionSourcesAndLayers(map);
        state.sourcesReady = true;
        synchronizeMinZoom(map);
        map.on('resize', () => synchronizeMinZoom(map));
        observeMapSize(map);
        bindMapInteractions(map);
        map.on('moveend', event => {
            // Status/selection text can itself resize a viewport-height map.
            // Do not alternate loading/ready heights or auto-retry an error.
            if (event.workspaceResize && diagnostics.lastState === 'error') return;
            scheduleLoad(LOAD_DEBOUNCE_MS, {quiet: event.workspaceResize === true});
        });
        requestRefresh();
        await replayPendingRefresh();
    } catch (error) {
        failMapInitialization(
                error,
                'Карта тренутно није доступна. Проверите локални пакет основне карте и покушајте поново.');
    }
}

function failMapInitialization(error, message) {
    diagnostics.lastError = errorName(error);
    state.sourcesReady = false;
    state.resizeObserver?.disconnect();
    state.resizeObserver = null;
    state.map?.remove();
    state.map = null;
    publicApi.map = null;
    setMapState('error', message);
}

function observeMapSize(map) {
    // Tag layout resizes rather than competing with MapLibre's own observer.
    // Ignore zero dimensions and keep the table-mode map laid out but invisible,
    // so background shared-view refreshes never use a collapsed/default canvas.
    // Metadata/form enhancement may already have changed the layout while the
    // style was loading, before the observer was installed.
    map.resize();
    let width = map.getContainer().clientWidth;
    let height = map.getContainer().clientHeight;
    state.resizeObserver = new ResizeObserver(() => {
        const container = map.getContainer();
        const nextWidth = container.clientWidth;
        const nextHeight = container.clientHeight;
        if (!nextWidth || !nextHeight || (width === nextWidth && height === nextHeight)) return;
        width = nextWidth;
        height = nextHeight;
        map.resize({workspaceResize: true}); // Recalculate the safe minimum and reload without layout feedback.
    });
    state.resizeObserver.observe(map.getContainer());
}

function configureTwoDimensionalCamera(map) {
    // Keep pinch zoom and keyboard pan/zoom, but remove every user rotation or
    // pitch path from this 2D auction-map consumer.
    map.touchZoomRotate.disableRotation();
    map.keyboard.disableRotation();
}

function bindFilterControls() {
    const results = elements.resultList.closest('.map-results');
    const resultsVisible = () => results.getClientRects().length && getComputedStyle(results).visibility !== 'hidden';
    results.addEventListener('scroll', () => {
        if (resultsVisible()) state.resultScrollTop = results.scrollTop;
    });
    window.addEventListener('eaukcija:workspace-mode', () => {
        if (resultsVisible()) results.scrollTop = state.resultScrollTop;
    });
    document.getElementById('map-retry').addEventListener('click', () => refreshNow());
    elements.filterForm.addEventListener('submit', event => {
        // With no usable basemap, normal GET submission still provides a working table.
        if (!state.map && !state.initializationPromise) return;
        event.preventDefault();
        if (!validDateRange()) return;
        const query = new URLSearchParams(state.appliedQuery);
        const draft = new FormData(elements.filterForm);
        for (const field of FILTER_FIELDS) {
            query.delete(field);
            for (const value of draft.getAll(field)) {
                if (value) query.append(field, value);
            }
        }
        query.set('page', '0');
        navigateFilters(query, false);
    });
    for (const name of ['from', 'to']) {
        elements.filterForm.elements.namedItem(name).addEventListener('input', () =>
            document.getElementById('map-to-filter').setCustomValidity(''));
    }
    elements.filterForm.addEventListener('input', event => {
        if (event.target.getAttribute('aria-invalid') === 'true') {
            event.target.removeAttribute('aria-invalid');
            event.target.removeAttribute('aria-errormessage');
        }
    });
    elements.filterReset.addEventListener('click', event => {
        event.preventDefault();
        const query = new URLSearchParams(state.appliedQuery);
        FILTER_FIELDS.forEach(field => query.delete(field));
        query.set('timeScope', 'not-ended'); query.set('page', '0');
        navigateFilters(query, true);
    });
    document.addEventListener('click', event => {
        const selected = event.target.closest('#shared-results .table-select');
        if (selected) {
            state.clusterSequence++;
            window.dispatchEvent(new Event('eaukcija:show-map'));
            if (state.selectedAuctionId !== selected.dataset.auctionId) state.selectedFeatureId = null;
            state.selectedAuctionId = selected.dataset.auctionId;
            state.detailsOpen = true;
            state.detailsDismissed = false;
            state.detailsTrigger = selected;
            writeUrlState();
            updateSelectionLayers();
            restoreSelectionFromFeatures();
            if (event.detail === 0) focusDetails();
            if (!state.features.some(f => String(f.properties.auctionId) === state.selectedAuctionId)) refreshNow();
            return;
        }
        const link = event.target.closest('#shared-results th a, #shared-results .pagination a');
        if (!link || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        const query = new URL(link.href).searchParams;
        if (state.selectedAuctionId) query.set('auction', state.selectedAuctionId);
        else query.delete('auction');
        navigateFilters(query, false);
    });
    window.addEventListener('popstate', () => {
        state.appliedQuery = new URL(window.location.href).searchParams;
        state.invalidFilter = false;
        // History restores shareable identity, never a historical DOM/open state.
        dismissDetails();
        state.selectedAuctionId = readSelectedAuction();
        state.selectedFeatureId = null;
        state.detailsDismissed = false;
        state.detailsTrigger = null;
        updateSelectionLayers();
        restoreSelectionFromFeatures();
        restoreFilterControls();
        refreshNow();
    });
}

function navigateFilters(query, restore) {
    state.appliedQuery = query;
    state.invalidFilter = false;
    const url = new URL(window.location.href); url.search = query.toString();
    window.history.pushState(null, '', url);
    if (restore) restoreFilterControls();
    refreshNow();
}

function restoreFilterControls() {
    for (const field of FILTER_FIELDS) {
        if (field === 'municipality') municipalitySelect.setValues(state.appliedQuery.getAll(field));
        else elements.filterForm.elements.namedItem(field).value = state.appliedQuery.get(field) || '';
    }
    document.getElementById('map-to-filter').setCustomValidity('');
    filterPresentation.update();
}

function refreshOptions(options) {
    if (!options) return;
    for (const name of ['category', 'status', 'municipality', 'placeName']) {
        if (!Array.isArray(options[name])) continue;
        if (name === 'municipality') {
            municipalitySelect.refreshOptions(options[name]);
            continue;
        }
        const control = elements.filterForm.elements.namedItem(name);
        const draft = control.value;
        const target = control.list || control;
        const empty = control.list ? [] : [new Option(control.options[0].text, '')];
        const values = [...new Set([...options[name], ...(draft ? [draft] : [])])];
        target.replaceChildren(...empty, ...values.map(value => new Option(value, value)));
        control.value = draft;
    }
}

function validDateRange() {
    const from = document.getElementById('map-from-filter');
    const to = document.getElementById('map-to-filter');
    to.setCustomValidity('');
    if (from.value && to.value && to.value < from.value) {
        to.setCustomValidity('Крајњи датум мора бити исти или после почетног.');
        to.reportValidity();
        return false;
    }
    return elements.filterForm.reportValidity();
}

function writeUrlState() {
    const query = state.appliedQuery;
    if (state.selectedAuctionId) query.set('auction', state.selectedAuctionId);
    else query.delete('auction');
    if (state.selectedAuctionId) state.lastUsableQuery.set('auction', state.selectedAuctionId);
    else state.lastUsableQuery.delete('auction');
    elements.filterForm.elements.namedItem('auction').value = state.selectedAuctionId || '';
    const url = new URL(window.location.href); url.search = query.toString();
    if (url.href !== window.location.href) window.history.pushState(null, '', url);
}

function replaceUrl(parameters) {
    const next = new URL(window.location.href);
    next.search = parameters.toString();
    window.history.replaceState(null, '', next);
}

function readSelectedAuction() {
    const selected = new URL(window.location.href).searchParams.get('auction');
    return validAuctionId(selected) ? selected : null;
}

function validAuctionId(value) {
    return typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value);
}

function scheduleLoad(delay = LOAD_DEBOUNCE_MS, {quiet = false} = {}) {
    window.clearTimeout(state.debounceTimer);
    abortActiveRequest();
    requestRefresh({quiet});
    if (!quiet) setMapState(
            'loading',
            state.features.length
                    ? 'Освежавање видљивог дела карте; претходни резултати остају приказани…'
                    : 'Учитавање аукција у видљивом делу карте…');
    state.debounceTimer = window.setTimeout(() => {
        state.debounceTimer = null;
        replayPendingRefresh();
    }, delay);
}

async function refreshNow() {
    window.clearTimeout(state.debounceTimer);
    state.debounceTimer = null;
    abortActiveRequest();
    requestRefresh();
    setMapState(
            'loading',
            state.features.length
                    ? 'Примена филтера; претходни резултати остају приказани док се карта освежава…'
                    : 'Примена филтера и учитавање аукција…');
    if (!state.map) {
        return initialize();
    }
    return replayPendingRefresh();
}

function requestRefresh({quiet = false} = {}) {
    // A resize must not downgrade an already pending explicit filter refresh.
    state.quietRefresh = quiet && (!state.pendingRefresh || state.quietRefresh);
    state.pendingRefresh = true;
    diagnostics.pendingRefresh = true;
}

async function replayPendingRefresh() {
    if (!state.pendingRefresh
            || !state.map
            || !state.sourcesReady
            || !state.map.isStyleLoaded()) {
        return;
    }
    if (!ensureRequestableViewport(state.map)) {
        return;
    }
    const quiet = state.quietRefresh;
    state.pendingRefresh = false;
    diagnostics.pendingRefresh = false;
    return loadViewport({quiet});
}

async function loadViewport({quiet = false} = {}) {
    if (!state.map || !state.sourcesReady || !state.map.isStyleLoaded()) {
        requestRefresh({quiet});
        return;
    }
    abortActiveRequest();
    const controller = new AbortController();
    state.activeRequest = controller;
    const sequence = ++state.requestSequence;
    diagnostics.requestsStarted++;
    diagnostics.lastError = null;
    if (!quiet) setMapState(
            'loading',
            state.features.length
                    ? 'Освежавање видљивог дела карте; претходни резултати остају приказани…'
                    : 'Учитавање аукција у видљивом делу карте…');

    try {
        const response = await fetch(viewportUrl(), {
            headers: {'Accept': 'application/geo+json'},
            cache: 'no-store',
            signal: controller.signal
        });
        if (!response.ok) {
            throw await mapResponseError(response);
        }
        const view = await response.json();
        const collection = validateCollection(view.map);
        if (sequence !== state.requestSequence || controller.signal.aborted) return;
        if (typeof view.resultsHtml !== 'string' || typeof view.query !== 'string') throw new Error('INVALID_VIEW_RESPONSE');
        // HTML is the same escaped, same-origin Thymeleaf fragment as the initial table,
        // never source description text from GeoJSON. The form is deliberately not replaced.
        const fragment = new DOMParser().parseFromString(view.resultsHtml, 'text/html').querySelector('#shared-results');
        if (!fragment) throw new Error('INVALID_VIEW_RESPONSE');
        replaceTableResults(fragment);
        refreshOptions(view.options);
        if (view.catalogue) {
            document.getElementById('catalogue-count').textContent = String(view.catalogue.total);
            document.getElementById('catalogue-details').textContent = String(view.catalogue.details);
        }
        const canonical = new URLSearchParams(view.query);
        if (state.selectedAuctionId) canonical.set('auction', state.selectedAuctionId);
        else canonical.delete('auction');
        state.appliedQuery = canonical;
        state.lastUsableQuery = new URLSearchParams(canonical);
        state.invalidFilter = false;
        replaceUrl(canonical);
        filterPresentation.update();
        elements.filterForm.querySelectorAll('[aria-invalid]').forEach(control => {
            control.removeAttribute('aria-invalid');
            control.removeAttribute('aria-errormessage');
        });
        document.getElementById('filter-state').textContent = '';
        state.features = collection.features;
        state.selectionStatus = collection.selection || null;
        diagnostics.requestsCompleted++;
        diagnostics.lastFeatureCount = collection.features.length;
        diagnostics.truncated = collection.truncated === true;
        updateSources(collection.features);
        renderResults(collection.features);
        elements.limitWarning.hidden = !collection.truncated;

        const counts = collection.counts;
        const summary = `Филтрирано аукција: ${counts.filteredAuctionCount}. `
                + `Мапирано аукција у приказу: ${counts.mappedAuctionCountInViewport}. `
                + `Објеката на карти: ${collection.numberReturned} од ${counts.featureCountInViewport}. `
                + `Без локације: ${counts.unmappedAuctionCount}. `
                + `Ван приказа: ${counts.filteredAuctionCount - counts.unmappedAuctionCount - counts.mappedAuctionCountInViewport}.`;
        const returnedAuctions = collection.returnedAuctionCount ?? new Set(collection.features.map(feature => feature.properties.auctionId)).size;
        document.getElementById('map-count-breakdown').textContent = summary + ` Учитано аукција: ${returnedAuctions}.`;
        document.getElementById('map-count-summary').textContent = `Филтрирано: ${counts.filteredAuctionCount} аукција · `
            + `У приказу: ${counts.mappedAuctionCountInViewport} аукција / ${counts.featureCountInViewport} објеката`
            + (collection.truncated ? ` — учитано ${collection.numberReturned} објеката (ограничено)` : '');
        const empty = !collection.features.length
                ? (state.appliedQuery.get('precision') === 'NONE'
                    ? ' Изабране су аукције без објављиве локације; карта нема ознаке.'
                    : ' Нема објеката у приказу за пресек критеријума; проверите датуме и временски опсег.') : '';
        setMapState(collection.features.length ? 'ready' : 'empty', (collection.features.length ? summary : empty)
                + (collection.truncated ? ' Приказ је ограничен: нису све аукције/објекти учитани; сузите област или филтере.' : ''));
        restoreSelectionFromFeatures();
    } catch (error) {
        if (sequence !== state.requestSequence || controller.signal.aborted) return;
        if (error?.name === 'AbortError') {
            return;
        }
        diagnostics.lastError = errorName(error);
        const retained = state.features.length
                ? ` Претходних ${state.features.length} резултата остаје приказано.`
                : '';
        if (error instanceof MapHttpError && error.clientError) {
            // A rejected draft is not a new canonical filter state. Keep the valid
            // URL/results and the user's edits, and do not auto-retry a deterministic 400.
            state.appliedQuery = new URLSearchParams(state.lastUsableQuery);
            state.invalidFilter = true;
            replaceUrl(state.appliedQuery);
            const field = error.field ? ` (${filterPresentation.label(error.field)})` : '';
            const detail = error.detail ? `: ${error.detail}` : '';
            setMapState(
                    'error',
                    `Захтев приказа није прихваћен${field}${detail}.${retained} Промените приказ или филтер.`);
            filterPresentation.update();
            if (FILTER_FIELDS.includes(error.field)) {
                const control = elements.filterForm.elements.namedItem(error.field);
                if (control instanceof HTMLElement) {
                    control.setAttribute('aria-invalid', 'true');
                    control.setAttribute('aria-errormessage', 'filter-state');
                }
                window.dispatchEvent(new CustomEvent('eaukcija:reveal-filter', {detail: {field: error.field}}));
            }
        } else {
            setMapState(
                    'error',
                    `Није могуће преузети аукције за овај приказ.${retained} Покушајте поново.`);
            document.getElementById('map-retry').hidden = false;
        }
    } finally {
        if (state.activeRequest === controller) {
            state.activeRequest = null;
        }
    }
}

function replaceTableResults(fragment) {
    const previous = document.getElementById('shared-results');
    const scrollLeft = previous.querySelector('.table-scroll')?.scrollLeft || 0;
    const focused = previous.contains(document.activeElement) ? document.activeElement : null;
    const sortIndex = [...previous.querySelectorAll('th a')].indexOf(focused);
    const auctionId = focused?.matches('.table-select') ? focused.dataset.auctionId : null;
    previous.replaceWith(fragment);
    const scroll = fragment.querySelector('.table-scroll');
    if (scroll) scroll.scrollLeft = scrollLeft;
    if (focused) {
        const next = sortIndex >= 0 ? fragment.querySelectorAll('th a')[sortIndex]
            : validAuctionId(auctionId) ? fragment.querySelector(`.table-select[data-auction-id="${auctionId}"]`)
                : null;
        (next || scroll)?.focus({preventScroll: true});
    }
}

function abortActiveRequest() {
    if (!state.activeRequest) {
        return;
    }
    const controller = state.activeRequest;
    state.activeRequest = null;
    diagnostics.requestsAborted++;
    controller.abort();
    state.requestSequence++;
}

function viewportUrl() {
    const bounds = state.map.getBounds();
    diagnostics.lastRequestAreaSquareKm = boundingBoxAreaSquareKm(bounds);
    const query = new URLSearchParams(state.appliedQuery);
    query.set('bbox', [
        bounds.getWest(), bounds.getSouth(), bounds.getEast(), bounds.getNorth()
    ].map(coordinate => coordinate.toFixed(6)).join(','));
    query.set('limit', String(RESULT_LIMIT));
    return `/api/auctions/view?${query.toString()}`;
}

function synchronizeMinZoom(map) {
    const minimum = requestableMinZoom(map);
    diagnostics.requestableMinZoom = minimum;
    if (Math.abs(map.getMinZoom() - minimum) > .005) {
        map.setMinZoom(minimum);
    }
}

function requestableMinZoom(map) {
    let lower = 0;
    let upper = map.getMaxZoom();
    for (let iteration = 0; iteration < 32; iteration++) {
        const candidate = (lower + upper) / 2;
        const area = boundingBoxAreaSquareKm(estimatedViewportBounds(map, candidate));
        if (area > MAX_BBOX_AREA_SQUARE_KM * REQUEST_AREA_SAFETY_FACTOR) {
            lower = candidate;
        } else {
            upper = candidate;
        }
    }
    return Math.min(map.getMaxZoom(), Math.ceil(upper * 100) / 100);
}

function estimatedViewportBounds(map, zoom) {
    const rectangle = map.getContainer().getBoundingClientRect();
    const center = map.getCenter();
    const worldSize = 512 * (2 ** zoom);
    const centerX = (center.lng + 180) / 360;
    const latitudeRadians = center.lat * Math.PI / 180;
    const centerY = (1 - Math.log(
            Math.tan(latitudeRadians) + (1 / Math.cos(latitudeRadians))) / Math.PI) / 2;
    const halfWidth = Math.max(rectangle.width, 1) / (2 * worldSize);
    const halfHeight = Math.max(rectangle.height, 1) / (2 * worldSize);
    return {
        west: (centerX - halfWidth) * 360 - 180,
        east: (centerX + halfWidth) * 360 - 180,
        north: latitudeFromMercatorY(centerY - halfHeight),
        south: latitudeFromMercatorY(centerY + halfHeight)
    };
}

function latitudeFromMercatorY(value) {
    return Math.atan(Math.sinh(Math.PI * (1 - 2 * value))) * 180 / Math.PI;
}

function boundingBoxAreaSquareKm(bounds) {
    const west = typeof bounds.getWest === 'function' ? bounds.getWest() : bounds.west;
    const east = typeof bounds.getEast === 'function' ? bounds.getEast() : bounds.east;
    const south = typeof bounds.getSouth === 'function' ? bounds.getSouth() : bounds.south;
    const north = typeof bounds.getNorth === 'function' ? bounds.getNorth() : bounds.north;
    const longitudeRadians = Math.abs((east - west) * Math.PI / 180);
    const latitudeFactor = Math.abs(
            Math.sin(north * Math.PI / 180) - Math.sin(south * Math.PI / 180));
    return EARTH_RADIUS_KM * EARTH_RADIUS_KM * longitudeRadians * latitudeFactor;
}

function ensureRequestableViewport(map) {
    synchronizeMinZoom(map);
    const area = boundingBoxAreaSquareKm(map.getBounds());
    diagnostics.lastRequestAreaSquareKm = area;
    if (Number.isFinite(area)
            && area <= MAX_BBOX_AREA_SQUARE_KM * REQUEST_AREA_SAFETY_FACTOR) {
        return true;
    }
    const scale = Number.isFinite(area) && area > 0
            ? .5 * Math.log2(area / (MAX_BBOX_AREA_SQUARE_KM * REQUEST_AREA_SAFETY_FACTOR))
            : 1;
    const targetZoom = Math.min(
            map.getMaxZoom(),
            Math.max(map.getMinZoom(), map.getZoom() + Math.max(scale, .1)));
    map.setMinZoom(Math.max(map.getMinZoom(), Math.ceil(targetZoom * 100) / 100));
    requestRefresh();
    setMapState(
            'loading',
            'Приказ је аутоматски увећан да би захтев остао у дозвољеном обиму; учитавање аукција…');
    map.jumpTo({zoom: targetZoom});
    return false;
}

async function mapResponseError(response) {
    let problem = null;
    if (response.status >= 400 && response.status < 500) {
        try {
            problem = await response.json();
        } catch (_error) {
            problem = null;
        }
    }
    return new MapHttpError(response.status, problem);
}

class MapHttpError extends Error {
    constructor(status, problem) {
        super(`MAP_HTTP_${status}`);
        this.name = 'MapHttpError';
        this.status = status;
        this.clientError = status >= 400 && status < 500;
        this.field = safeProblemText(problem?.field, 64);
        this.detail = safeProblemText(problem?.detail, 300);
    }
}

function safeProblemText(value, maximumLength) {
    return typeof value === 'string' ? value.trim().slice(0, maximumLength) : '';
}

function validateCollection(value) {
    if (!value
            || value.type !== 'FeatureCollection'
            || !Array.isArray(value.features)
            || typeof value.truncated !== 'boolean'
            || value.features.some(feature => !validFeature(feature))) {
        throw new Error('INVALID_MAP_RESPONSE');
    }
    return value;
}

function selectOptionValues(elementId) {
    return Object.freeze([...document.getElementById(elementId).options]
            .map(option => option.value)
            .filter(Boolean));
}

function optionLabel(elementId, value) {
    const option = [...document.getElementById(elementId).options]
            .find(candidate => candidate.value === value);
    return option?.textContent?.trim() || null;
}

function assertPrecisionContract() {
    const styles = Object.keys(PRECISIONS);
    if (styles.length !== FILTER_OPTIONS.precision.filter(value => value !== 'NONE').length
            || styles.some(precision => !FILTER_OPTIONS.precision.includes(precision))) {
        throw new Error('MAP_PRECISION_CONTRACT_MISMATCH');
    }
}

function validFeature(feature) {
    const properties = feature?.properties;
    return feature?.type === 'Feature'
            && typeof feature.id === 'string'
            && ['Point', 'Polygon', 'MultiPolygon'].includes(feature?.geometry?.type)
            && Array.isArray(feature?.geometry?.coordinates)
            && (feature.geometry.type === 'Point' || (feature.marker?.type === 'Point'
                && Array.isArray(feature.marker.coordinates) && feature.marker.coordinates.length === 2
                && feature.marker.coordinates.every(Number.isFinite)))
            && properties
            && validAuctionId(String(properties.auctionId))
            && typeof properties.title === 'string'
            && FILTER_OPTIONS.precision.includes(properties.precision)
            && Object.hasOwn(PRECISIONS, properties.precision)
            && typeof properties.detailUrl === 'string';
}

function updateSources(features) {
    // GeoJSON tiling can coerce a string id like "34001:hash" to the number 34001.
    // Promote the existing API identity explicitly; never confuse sibling properties.
    const rendered = features.map(feature => ({...feature,
        properties: {...feature.properties, mapFeatureId: feature.id}}));
    state.clusterSequence++; // In-flight worker leaves belong to the previous source revision.
    const points = rendered.map(markerFeature);
    const areas = rendered.filter(feature => feature.geometry.type !== 'Point');
    state.map.getSource(POINT_SOURCE).setData({type: 'FeatureCollection', features: points});
    state.map.getSource(AREA_SOURCE).setData({type: 'FeatureCollection', features: areas});
    updateSelectionLayers();
}

function markerFeature(feature) {
    return {...feature, geometry: feature.geometry.type === 'Point' ? feature.geometry : feature.marker,
        properties: {...feature.properties, mapFeatureId: feature.id, parcelBoundary: feature.geometry.type !== 'Point'}};
}

function addAuctionSourcesAndLayers(map) {
    for (const [precision, presentation] of Object.entries(PRECISIONS)) {
        map.addImage(iconName(precision), markerImage(presentation), {pixelRatio: 2});
    }

    map.addSource(POINT_SOURCE, {
        type: 'geojson',
        data: EMPTY_COLLECTION,
        promoteId: 'mapFeatureId',
        cluster: true,
        maxzoom: 21, // Keep coincident groups resolvable through the supported camera maximum (20).
        clusterMaxZoom: 20,
        clusterRadius: 52
    });
    map.addSource(AREA_SOURCE, {type: 'geojson', data: EMPTY_COLLECTION, promoteId: 'mapFeatureId',
        maxzoom: 20, tolerance: 0}); // Do not deliberately simplify cadastral boundaries.
    map.addSource(SELECTION_SOURCE, {type: 'geojson', data: EMPTY_COLLECTION, promoteId: 'mapFeatureId'});

    for (const [precision, presentation] of Object.entries(PRECISIONS)) {
        map.addLayer({
            id: areaFillLayer(precision),
            type: 'fill',
            source: AREA_SOURCE,
            minzoom: NEIGHBOURHOOD_ZOOM,
            filter: ['==', ['get', 'precision'], precision],
            paint: {'fill-color': presentation.color,
                'fill-opacity': ['step', ['zoom'], .25, CLOSE_ZOOM, .12]}
        });
        map.addLayer({
            id: areaLineLayer(precision),
            type: 'line',
            source: AREA_SOURCE,
            minzoom: NEIGHBOURHOOD_ZOOM,
            filter: ['==', ['get', 'precision'], precision],
            paint: {
                'line-color': presentation.color,
                'line-width': 3,
                'line-dasharray': presentation.dash
            }
        });
    }

    // The selection outline must sit above every precision fill/outline.
    map.addLayer({
        id: SELECTED_AREA_LAYER,
        type: 'line',
        source: AREA_SOURCE,
        filter: ['==', ['get', 'auctionId'], -1],
        paint: {'line-color': '#111827', 'line-width': 6, 'line-opacity': .9}
    });

    for (const precision of Object.keys(PRECISIONS)) {
        map.addLayer({
            id: pointLayer(precision),
            type: 'symbol',
            source: POINT_SOURCE,
            filter: ['all', ['!', ['has', 'point_count']], ['==', ['get', 'precision'], precision]],
            layout: {
                'icon-image': iconName(precision),
                // Retain a small activation cue even for sub-pixel parcels at maximum zoom.
                'icon-size': ['step', ['zoom'], 1, CLOSE_ZOOM,
                    ['case', ['get', 'parcelBoundary'], .6, 1]],
                'icon-allow-overlap': true,
                'icon-ignore-placement': true
            }
        });
    }

    map.addLayer({
        id: CLUSTER_LAYER,
        type: 'circle',
        source: POINT_SOURCE,
        filter: ['has', 'point_count'],
        paint: {
            'circle-color': [
                'step', ['get', 'point_count'], '#174ea6', 10, '#713f98', 50, '#8a2c20'
            ],
            'circle-radius': ['step', ['get', 'point_count'], 20, 10, 25, 50, 31],
            'circle-stroke-color': '#fff',
            'circle-stroke-width': 3
        }
    });
    map.addLayer({
        id: CLUSTER_COUNT_LAYER,
        type: 'symbol',
        source: POINT_SOURCE,
        filter: ['has', 'point_count'],
        layout: {
            'text-field': ['get', 'point_count_abbreviated'],
            'text-font': ['Noto Sans Regular'],
            'text-size': 13,
            'text-allow-overlap': true,
            'text-ignore-placement': true
        },
        paint: {'text-color': '#fff'}
    });
    // An unclustered overlay keeps the chosen PROPERTY identifiable even inside a cluster
    // or after closing details. It is never included in cluster/product counts.
    map.addLayer({
        id: SELECTED_POINT_LAYER,
        type: 'circle',
        source: SELECTION_SOURCE,
        paint: {'circle-radius': 34, 'circle-color': '#fff', 'circle-opacity': 0,
            'circle-stroke-color': '#111827', 'circle-stroke-width': 3, 'circle-stroke-opacity': 1}
    });
}

function markerImage(presentation) {
    const size = 40;
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const context = canvas.getContext('2d');
    context.clearRect(0, 0, size, size);
    context.fillStyle = presentation.color;
    context.strokeStyle = '#fff';
    context.lineWidth = 4;
    context.lineJoin = 'round';
    context.beginPath();
    drawShape(context, presentation.shape, size);
    context.closePath();
    context.fill();
    context.stroke();
    return context.getImageData(0, 0, size, size);
}

function drawShape(context, shape, size) {
    const center = size / 2;
    const edge = 6;
    if (shape === 'circle') {
        context.arc(center, center, 13, 0, Math.PI * 2);
        return;
    }
    if (shape === 'square') {
        context.rect(7, 7, 26, 26);
        return;
    }
    if (shape === 'diamond') {
        context.moveTo(center, 4);
        context.lineTo(size - 4, center);
        context.lineTo(center, size - 4);
        context.lineTo(4, center);
        return;
    }
    if (shape === 'triangle') {
        context.moveTo(center, 4);
        context.lineTo(size - 4, size - 5);
        context.lineTo(4, size - 5);
        return;
    }
    if (shape === 'hexagon') {
        context.moveTo(11, edge);
        context.lineTo(29, edge);
        context.lineTo(size - 3, center);
        context.lineTo(29, size - edge);
        context.lineTo(11, size - edge);
        context.lineTo(3, center);
        return;
    }
    context.moveTo(14, 4);
    context.lineTo(26, 4);
    context.lineTo(26, 14);
    context.lineTo(36, 14);
    context.lineTo(36, 26);
    context.lineTo(26, 26);
    context.lineTo(26, 36);
    context.lineTo(14, 36);
    context.lineTo(14, 26);
    context.lineTo(4, 26);
    context.lineTo(4, 14);
    context.lineTo(14, 14);
}

function bindMapInteractions(map) {
    const layers = [CLUSTER_LAYER, ...Object.keys(PRECISIONS)
            .flatMap(precision => [pointLayer(precision), areaFillLayer(precision), areaLineLayer(precision)])];
    layers.forEach(layer => setPointerCursor(map, layer));
    const activate = (point, keyboard = false) => {
        // Prefer an actual hit; enlarge only the screen-space activation target, never geometry.
        let hits = map.queryRenderedFeatures(point, {layers});
        if (!hits.length) hits = map.queryRenderedFeatures([
            [point.x - HIT_RADIUS_PX, point.y - HIT_RADIUS_PX],
            [point.x + HIT_RADIUS_PX, point.y + HIT_RADIUS_PX]
        ], {layers});
        const cluster = hits.find(feature => feature.layer.id === CLUSTER_LAYER);
        if (cluster) return showCluster(cluster);
        const features = canonicalFeatures(hits);
        if (features.length > 1) renderClusterSelection(features, features.length, coincidentLocations(features));
        else if (features.length) selectFeature(features[0], {trigger: map.getCanvas(), focusDetails: keyboard});
    };
    map.on('click', event => {
        if (event.originalEvent?.target === map.getCanvas()) activate(event.point);
    });
    map.getCanvas().addEventListener('keydown', event => {
        if (event.key !== 'Enter' || event.isComposing) return;
        event.preventDefault();
        activate({x: map.getContainer().clientWidth / 2, y: map.getContainer().clientHeight / 2}, true);
    });
}

function canonicalFeatures(features) {
    const ids = new Set(features.map(feature => feature.properties.mapFeatureId || feature.id));
    return state.features.filter(feature => ids.has(feature.id));
}

function coincidentLocations(features) {
    if (!features.length) return false;
    const [x, y] = representativeCoordinate(features[0]);
    return features.every(feature => {
        const point = representativeCoordinate(feature);
        return point[0] === x && point[1] === y; // No jitter or merging nearby, distinct locations.
    });
}

function bindDetailsDismissal() {
    const toggle = document.getElementById('selection-toggle');
    toggle.addEventListener('click', event => {
        if (document.getElementById('workspace').dataset.mode === 'table') window.dispatchEvent(new Event('eaukcija:show-map'));
        const feature = selectedFeature();
        if (feature) selectFeature(feature, {trigger: toggle, focusDetails: event.detail === 0});
        else {
            window.dispatchEvent(new Event('eaukcija:show-map'));
            state.detailsDismissed = false;
            restoreSelectionFromFeatures();
            elements.selection.focus({preventScroll: true});
        }
    });
    window.addEventListener('eaukcija:workspace-mode', () => {
        if (state.detailsOpen && selectedFeature()) showPopup(selectedFeature());
        updateDetailsControl();
    });
    // Capture runs BEFORE result/table/MapLibre opening handlers. The opening click
    // cannot bubble back here and immediately dismiss the newly opened details.
    document.addEventListener('click', event => {
        if ((state.detailsOpen || !elements.selection.hidden) && !state.popup?.getElement().contains(event.target)
                && !document.getElementById('rail-details').contains(event.target)
                && !toggle.contains(event.target) && !elements.selection.contains(event.target)) {
            // Hide the summary after hit testing: removing it now shifts the map
            // under this same pointer event. Never reclaim the clicked control's focus.
            dismissDetails({deferSummary: true});
        }
    }, {capture: true});
    document.addEventListener('keydown', event => {
        // Native disclosures (e.g. municipalities) own their handled Escape first.
        if (event.key === 'Escape' && !event.defaultPrevented && !event.isComposing
                && (state.detailsOpen || !elements.selection.hidden)) {
            event.preventDefault();
            dismissDetails({restoreFocus: true});
        }
    });
}

function setPointerCursor(map, layer) {
    map.on('mouseenter', layer, () => {
        map.getCanvas().style.cursor = 'pointer';
    });
    map.on('mouseleave', layer, () => {
        map.getCanvas().style.cursor = '';
    });
}

async function showCluster(cluster) {
    if (!cluster) {
        return;
    }
    state.detailsDismissed = false;
    const sequence = ++state.clusterSequence;
    const source = state.map.getSource(POINT_SOURCE);
    const clusterId = Number(cluster.properties.cluster_id);
    const count = Number(cluster.properties.point_count);
    try {
        const leaves = await source.getClusterLeaves(clusterId, Math.min(count, RESULT_LIMIT), 0);
        if (sequence !== state.clusterSequence) return;
        const features = canonicalFeatures(leaves);
        const coincident = features.length === count && coincidentLocations(features);
        diagnostics.lastClusterError = null;
        if (!coincident && state.map.getZoom() < state.map.getMaxZoom()) {
            const expansion = await source.getClusterExpansionZoom(clusterId);
            if (sequence !== state.clusterSequence) return;
            const zoom = Math.min(expansion, state.map.getMaxZoom());
            if (zoom > state.map.getZoom() + .01) {
                dismissDetails();
                state.map.easeTo({center: cluster.geometry.coordinates, zoom,
                    duration: reducedMotion() ? 0 : 300});
                return;
            }
        }
        // Coincident locations, terminal zoom, or no possible camera progress: always a chooser.
        renderClusterSelection(features, count, coincident);
    } catch (_error) {
        if (sequence !== state.clusterSequence) return;
        diagnostics.lastClusterError = 'CLUSTER_CHANGED';
        renderClusterError();
    }
}

function renderClusterError() {
    state.detailsOpen = false;
    closePopup();
    elements.selection.replaceChildren();
    elements.selection.setAttribute('role', 'alert');
    elements.selection.setAttribute('aria-live', 'assertive');
    const message = document.createElement('p');
    message.textContent = 'Група аукција се променила током освежавања. Активирајте групу поново.';
    elements.selection.append(message);
    window.dispatchEvent(new Event('eaukcija:show-map'));
    elements.selection.hidden = state.detailsDismissed;
    if (!elements.selection.hidden) elements.selection.focus({preventScroll: true});
}

function renderClusterSelection(features, total, coincident) {
    state.clusterSequence++; // An overlap chooser also supersedes older asynchronous cluster work.
    state.detailsDismissed = false;
    state.detailsOpen = false;
    state.detailsTrigger = state.map.getCanvas();
    closePopup();
    window.dispatchEvent(new Event('eaukcija:show-map'));
    elements.selection.replaceChildren();
    elements.selection.removeAttribute('role');
    elements.selection.removeAttribute('aria-live');
    const heading = document.createElement('h4');
    heading.textContent = `${total} објеката ${coincident ? 'на овој локацији' : 'у географској групи'} (${new Set(features.map(f => f.properties.auctionId)).size} учитаних аукција)`;
    elements.selection.append(heading);
    for (const [index, feature] of features.entries()) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'map-selection-button';
        button.dataset.featureId = feature.id;
        button.textContent = `${feature.properties.title} — ${precisionLabel(feature)} · објекат ${index + 1}`;
        bindFeatureSelection(button, feature);
        elements.selection.append(button);
    }
    if (features.length < total) {
        const note = document.createElement('p');
        note.textContent = `Приказано ${features.length} од ${total}; сузите приказ карте за остале.`;
        elements.selection.append(note);
    }
    elements.selection.hidden = state.detailsDismissed;
    if (!elements.selection.hidden) elements.selection.focus({preventScroll: true});
}

function renderResults(features) {
    const scroll = elements.resultList.closest('.map-results');
    if (scroll.getClientRects().length && getComputedStyle(scroll).visibility !== 'hidden') {
        state.resultScrollTop = scroll.scrollTop;
    }
    const focusedId = elements.resultList.contains(document.activeElement)
            ? document.activeElement.dataset.featureId : null;
    elements.resultList.replaceChildren();
    const selectedId = selectedFeature()?.id;
    elements.resultCount.textContent = String(features.length);
    elements.resultCount.setAttribute('aria-label', `${features.length} објеката, ${new Set(features.map(f => f.properties.auctionId)).size} аукција`);
    for (const feature of features) {
        const item = document.createElement('li');
        item.dataset.precision = feature.properties.precision;
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'map-result-button';
        button.dataset.auctionId = String(feature.properties.auctionId);
        button.dataset.featureId = feature.id;
        button.setAttribute('aria-current', feature.id === selectedId ? 'true' : 'false');

        const title = document.createElement('span');
        title.className = 'map-result-title';
        title.textContent = feature.properties.title;
        const meta = document.createElement('span');
        meta.className = 'map-result-meta';
        meta.textContent = `${precisionLabel(feature)} · ${formatAmount(feature.properties)}`;
        button.append(title, meta);
        bindFeatureSelection(button, feature, {moveMap: true});
        item.append(button);
        elements.resultList.append(item);
    }
    // Preserve the same logical keyboard position, not a global focus transfer.
    scroll.scrollTop = state.resultScrollTop;
    if (focusedId) resultTrigger(focusedId)?.focus({preventScroll: true});
}

function bindFeatureSelection(button, feature, options = {}) {
    bindActivation(button, activation => selectFeature(feature, {...options, ...activation, trigger: button}));
}

function bindActivation(button, activate) {
    let keyboardActivation = false;
    button.addEventListener('keydown', event => {
        if (event.key === 'Enter' || event.key === ' ') {
            keyboardActivation = true;
        }
    });
    button.addEventListener('click', event => {
        const focusDetails = keyboardActivation || event.detail === 0;
        keyboardActivation = false;
        activate({focusDetails});
    });
}

function selectFeature(feature, options = {}) {
    state.clusterSequence++; // A late chooser response cannot replace an explicit property activation.
    // Use full viewport geometry/properties rather than a clipped rendered tile or stale cluster leaf.
    const featureId = feature.properties.mapFeatureId || feature.id;
    feature = state.features.find(candidate => candidate.id === featureId);
    if (!feature) {
        renderClusterError(); // A stale chooser/tile must never restore an ineligible property.
        return;
    }
    const auctionId = String(feature.properties.auctionId);
    if (!validAuctionId(auctionId)) {
        return;
    }
    state.selectedAuctionId = auctionId;
    state.selectedFeatureId = feature.id;
    state.detailsOpen = true;
    state.detailsDismissed = false;
    state.detailsTrigger = options.trigger || null;
    diagnostics.selectedAuctionId = auctionId;
    writeUrlState();
    updateSelectionLayers();
    updateResultSelection();
    renderSelectedSummary(feature);
    showPopup(feature);
    if (options.moveMap && feature.properties.precision === 'PARCEL'
            && document.querySelector('.auction-map-panel')?.dataset.fitParcels === 'true'
            && ['Polygon', 'MultiPolygon'].includes(feature.geometry.type)) {
        const pairs = [];
        collectCoordinatePairs(feature.geometry.coordinates, pairs);
        const bounds = pairs.reduce((box, point) => [
            [Math.min(box[0][0], point[0]), Math.min(box[0][1], point[1])],
            [Math.max(box[1][0], point[0]), Math.max(box[1][1], point[1])]
        ], [[Infinity, Infinity], [-Infinity, -Infinity]]);
        if (pairs.length) state.map.fitBounds(bounds, {
            padding: 48, maxZoom: 17, duration: reducedMotion() ? 0 : 300
        });
    } else if (options.moveMap) {
        state.map.easeTo({
            center: representativeCoordinate(feature),
            duration: reducedMotion() ? 0 : 300
        });
    }
    if (options.focusDetails) focusDetails();
}

function renderSelectedSummary(feature) {
    if (!state.summaryView?.summary.isConnected) {
        elements.selection.replaceChildren();
        elements.selection.removeAttribute('role');
        elements.selection.removeAttribute('aria-live');
        const heading = document.createElement('h4');
        heading.textContent = 'Изабрана аукција';
        const summary = document.createElement('p');
        const reopen = document.createElement('button');
        reopen.type = 'button';
        reopen.className = 'map-selection-reopen';
        reopen.textContent = 'Отвори детаље';
        bindActivation(reopen, options => {
            const selected = selectedFeature();
            if (!selected) return;
            selectFeature(selected, {...options, trigger: reopen});
        });
        elements.selection.append(heading, summary, reopen);
        state.summaryView = {summary, reopen, sourceLink: null};
    }
    const view = state.summaryView;
    view.summary.textContent = `${feature.properties.title} — ${precisionLabel(feature)}. ${precisionExplanation(feature)}`;
    view.sourceLink = updateSourceLink(elements.selection, view.sourceLink, feature,
            'Отвори изабрану аукцију на порталу еАукција');
    if (view.sourceLink) view.sourceLink.className = 'map-selection-source';
    elements.selection.hidden = state.detailsDismissed;
    updateDetailsControl();
}

function updateResultSelection() {
    const selectedId = selectedFeature()?.id;
    for (const row of document.querySelectorAll('#shared-results tr[data-auction-id]')) {
        row.setAttribute('aria-selected', String(row.dataset.auctionId === state.selectedAuctionId));
    }
    for (const button of elements.resultList.querySelectorAll('.map-result-button')) {
        button.setAttribute(
                'aria-current',
                button.dataset.featureId === selectedId ? 'true' : 'false');
    }
}

function updateSelectionLayers() {
    if (!state.map?.getLayer(SELECTED_POINT_LAYER)) {
        return;
    }
    const selected = selectedFeature();
    state.map.getSource(SELECTION_SOURCE).setData({type: 'FeatureCollection',
        features: selected ? [markerFeature(selected)] : []});
    state.map.setFilter(SELECTED_AREA_LAYER, ['==', ['get', 'mapFeatureId'], selected?.id || '']);
}

function restoreSelectionFromFeatures() {
    diagnostics.selectedAuctionId = state.selectedAuctionId;
    const toggle = document.getElementById('selection-toggle');
    toggle.hidden = !state.selectedAuctionId;
    toggle.textContent = state.selectedAuctionId ? `Избор: ${state.selectedAuctionId}` : '';
    updateResultSelection();
    // A resize/refresh must not replace the cluster chooser while the user is
    // choosing a property. Explicit feature/table activation bypasses it.
    if (!state.detailsOpen && !state.detailsDismissed && !elements.selection.hidden
            && elements.selection.querySelector('.map-selection-button')) return;
    if (!state.selectedAuctionId) {
        elements.selection.hidden = true;
        closePopup();
        return;
    }
    const selected = selectedFeature();
    if (selected) {
        state.selectedFeatureId = selected.id;
        updateSelectionLayers();
        toggle.textContent = `Избор: ${state.selectedAuctionId} · ${precisionLabel(selected)}`;
        renderSelectedSummary(selected);
        if (state.detailsOpen) showPopup(selected);
        else closePopup();
    } else {
        elements.selection.replaceChildren();
        const text = document.createElement('p');
        const reasons = {
            OUTSIDE_FILTERS: 'Изабрана аукција не одговара примењеним филтерима.',
            UNMAPPED: 'Изабрана аукција нема објављиву локацију; није додата ознака на карту.',
            OUTSIDE_VIEWPORT: 'Изабрана аукција има локацију ван видљивог дела карте.',
            LIMIT: 'Изабрана аукција је у приказу, али изван ограниченог броја учитаних објеката.',
            NOT_FOUND: 'Изабрана аукција није у локалном каталогу.',
            VISIBLE: 'Изабрани објекат аукције није у учитаном приказу; други објекти исте аукције могу бити видљиви.'
        };
        const code = String(state.selectionStatus?.auctionId) === state.selectedAuctionId ? state.selectionStatus.state : null;
        const shortReasons = {OUTSIDE_FILTERS: 'ван филтера', UNMAPPED: 'без локације', OUTSIDE_VIEWPORT: 'ван приказа',
            LIMIT: 'изван ограничења', NOT_FOUND: 'није пронађена', VISIBLE: 'објекат није учитан'};
        toggle.textContent += ` · ${shortReasons[code] || 'објекат није учитан'} — детаљи`;
        text.textContent = (reasons[code] || 'Изабрана аукција није у видљивом делу карте, не одговара филтерима или нема објављиву локацију.') + ' Избор је сачуван.';
        elements.selection.append(text);
        elements.selection.hidden = state.detailsDismissed;
        closePopup();
    }
}

function selectedFeature() {
    return state.features.find(feature => String(feature.properties.auctionId) === state.selectedAuctionId
            && (!state.selectedFeatureId || feature.id === state.selectedFeatureId));
}

function showPopup(feature) {
    if (!state.detailsOpen) return;
    if (!state.popupView) {
        const content = document.createElement('article');
        content.id = 'auction-popup-details';
        content.className = 'map-popup';
        content.tabIndex = -1;
        const title = document.createElement('h3');
        const details = document.createElement('dl');
        const amount = appendDetail(details, 'Цена', '');
        const end = appendDetail(details, 'Завршетак', '');
        const status = appendDetail(details, 'Статус', '');
        const precision = appendDetail(details, 'Прецизност', '');
        const explanation = document.createElement('p');
        content.append(title, details, explanation);
        state.popupView = {content, title, amount, end, status, precision, explanation, sourceLink: null, mapsLink: null};
    }
    const rail = document.getElementById('rail-details');
    const inRail = document.getElementById('workspace').dataset.mode === 'results';
    if (inRail) {
        if (state.popup) {
            const popup = state.popup;
            state.popup = null;
            popup.remove(); // Transport change, not dismissal; retain the same details nodes.
        }
        if (!rail.contains(state.popupView.content)) {
            const close = document.createElement('button');
            close.type = 'button';
            close.className = 'rail-details-close';
            close.textContent = 'Назад на резултате';
            close.setAttribute('aria-label', 'Затвори детаље аукције — назад на резултате');
            close.addEventListener('click', () => dismissDetails({restoreFocus: true}));
            rail.replaceChildren(state.popupView.content, close);
        }
        rail.hidden = false;
    } else if (!state.popup) {
        rail.hidden = true;
        const content = state.popupView.content;
        const popup = new Popup({
            closeButton: true,
            closeOnClick: false, // One document-level dismissal path, including controls outside the map.
            focusAfterOpen: false,
            maxWidth: '310px'
        }).setLngLat(representativeCoordinate(feature)).setDOMContent(content).addTo(state.map);
        state.popup = popup;
        popup.on('close', () => {
            if (state.popup !== popup) return; // Internal teardown is not user dismissal.
            state.popup = null;
            state.popupView = null;
            dismissDetails();
        });
        const close = popup.getElement().querySelector('.maplibregl-popup-close-button');
        close.title = 'Затвори детаље аукције';
        // The built-in listener has already removed the popup and recorded dismissal.
        close.addEventListener('click', () => restoreDetailsFocus());
    }
    // Keep popup and focusable nodes connected during background data/geometry updates.
    const view = state.popupView;
    view.content.dataset.featureId = feature.id;
    view.content.setAttribute('aria-label', `Детаљи аукције ${feature.properties.title}`);
    view.title.textContent = feature.properties.title;
    view.amount.textContent = formatAmount(feature.properties);
    view.end.textContent = formatEndTime(feature.properties.endTime);
    view.status.textContent = statusLabel(feature.properties.sourceStatus);
    view.precision.textContent = precisionLabel(feature);
    view.explanation.textContent = precisionExplanation(feature);
    view.sourceLink = updateSourceLink(view.content, view.sourceLink, feature, 'Отвори на порталу еАукција');
    view.mapsLink = updateMapsLink(view.content, view.mapsLink, feature);
    state.popup?.setLngLat(representativeCoordinate(feature));
    updateDetailsControl();
}

function updateSourceLink(container, link, feature, text) {
    const url = allowlistedSourceUrl(feature.properties.detailUrl, feature.properties.auctionId);
    if (!url) {
        link?.remove();
        return null;
    }
    if (link) link.href = url;
    else {
        link = createSourceLink(feature, text);
        container.append(link);
    }
    return link;
}

function updateMapsLink(container, link, feature) {
    const url = googleMapsUrl(feature);
    if (!url) {
        link?.remove();
        return null;
    }
    if (link) {
        link.href = url;
        return link;
    }
    const mapsLink = document.createElement('a');
    mapsLink.href = url;
    mapsLink.target = '_blank';
    mapsLink.rel = 'noopener noreferrer';
    mapsLink.textContent = 'Отвори локацију у Google Maps';
    container.append(mapsLink);
    return mapsLink;
}

// Externally hosted, user-initiated navigation only. The offline asset
// contract governs resources this page loads by itself; it does not forbid a
// link a person chooses to follow. The URL mirrors the allowlistedSourceUrl
// discipline: fixed origin and path, with a query built exclusively from
// fixed-precision coordinate numbers derived from the reviewed geometry.
function googleMapsUrl(feature) {
    const [longitude, latitude] = representativeCoordinate(feature);
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude)
            || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
        return null;
    }
    const url = new URL('https://www.google.com/maps/search/');
    url.searchParams.set('api', '1');
    url.searchParams.set('query', `${latitude.toFixed(6)},${longitude.toFixed(6)}`);
    return url.href;
}

function updateDetailsControl() {
    const toggle = document.getElementById('selection-toggle');
    elements.selection.dataset.detailsOpen = String(state.detailsOpen && !!state.popupView
        && document.getElementById('workspace').dataset.mode === 'results');
    toggle.hidden = !state.selectedAuctionId;
    toggle.setAttribute('aria-expanded', String(state.detailsOpen));
    toggle.setAttribute('aria-controls', state.popupView ? 'auction-popup-details' : 'map-selection');
    const feature = selectedFeature();
    if (feature) toggle.textContent = `Избор: ${state.selectedAuctionId} · ${precisionLabel(feature)}`;
    const reopen = state.summaryView?.reopen;
    if (!reopen?.isConnected) return;
    reopen.setAttribute('aria-expanded', String(state.detailsOpen));
    if (state.detailsOpen) reopen.setAttribute('aria-controls', 'auction-popup-details');
    else reopen.removeAttribute('aria-controls');
}

function focusDetails() {
    (state.popupView?.sourceLink || state.popupView?.content || elements.selection).focus({preventScroll: true});
}

function resultTrigger(featureId) {
    return [...elements.resultList.querySelectorAll('.map-result-button')]
            .find(button => button.dataset.featureId === featureId);
}

function restoreDetailsFocus() {
    const candidates = [state.detailsTrigger, resultTrigger(state.selectedFeatureId),
        state.map?.getCanvas(), state.summaryView?.reopen, elements.selection];
    const trigger = candidates.find(element => element?.isConnected && element.getClientRects().length
            && getComputedStyle(element).visibility === 'visible' && !element.closest('[inert]'));
    trigger?.focus({preventScroll: true});
}

function dismissDetails({restoreFocus = false, deferSummary = false} = {}) {
    state.clusterSequence++;
    state.detailsOpen = false;
    state.detailsDismissed = true;
    document.getElementById('selection-toggle').setAttribute('aria-expanded', 'false');
    if (deferSummary) {
        window.setTimeout(() => {
            if (state.detailsDismissed) elements.selection.hidden = true;
        }, 0);
    } else {
        elements.selection.hidden = true;
    }
    closePopup();
    if (restoreFocus) restoreDetailsFocus();
}

function createSourceLink(feature, text) {
    const sourceUrl = allowlistedSourceUrl(feature.properties.detailUrl, feature.properties.auctionId);
    if (!sourceUrl) {
        return null;
    }
    const sourceLink = document.createElement('a');
    sourceLink.href = sourceUrl;
    sourceLink.target = '_blank';
    sourceLink.rel = 'noopener noreferrer';
    sourceLink.textContent = text;
    return sourceLink;
}

function appendDetail(list, termText, valueText) {
    const term = document.createElement('dt');
    term.textContent = termText;
    const value = document.createElement('dd');
    value.textContent = valueText;
    list.append(term, value);
    return value;
}

function allowlistedSourceUrl(value, auctionId) {
    try {
        const url = new URL(value);
        if (url.origin !== 'https://eaukcija.sud.rs'
                || url.pathname !== '/'
                || url.search
                || url.hash !== `#/aukcije/${auctionId}`) {
            return null;
        }
        return url.href;
    } catch (_error) {
        return null;
    }
}

function closePopup() {
    // Teardown for unavailable geometry is distinct from dismissal. Neither changes selection.
    const popup = state.popup;
    state.popup = null;
    state.popupView?.content.remove();
    state.popupView = null;
    popup?.remove();
    const rail = document.getElementById('rail-details');
    rail.replaceChildren();
    rail.hidden = true;
    updateDetailsControl();
}

function initialMapNumber(name, min, max, fallback) {
    const value = Number(document.querySelector('.auction-map-panel')?.dataset[name]);
    return Number.isFinite(value) && value >= min && value <= max ? value : fallback;
}

function representativeCoordinate(feature) {
    return feature.geometry.type === 'Point' ? feature.geometry.coordinates : feature.marker.coordinates;
}

function collectCoordinatePairs(value, output) {
    if (Array.isArray(value)
            && value.length >= 2
            && typeof value[0] === 'number'
            && typeof value[1] === 'number') {
        output.push(value);
        return;
    }
    if (Array.isArray(value)) {
        value.forEach(child => collectCoordinatePairs(child, output));
    }
}

function precisionLabel(feature) {
    return optionLabel('map-precision-filter', feature.properties.precision)
            || 'Непозната прецизност';
}

function precisionExplanation(feature) {
    return PRECISIONS[feature.properties.precision]?.explanation
            || 'Прецизност локације није позната.';
}

function statusLabel(value) {
    return optionLabel('map-status-filter', value) || value || 'Статус није познат';
}

function formatAmount(properties) {
    if (properties.amount === null || properties.amount === undefined) {
        return 'Цена није наведена';
    }
    if (!RSD_AMOUNT_FORMATTER) {
        return `${properties.amount} RSD`;
    }
    try {
        return RSD_AMOUNT_FORMATTER.format(properties.amount);
    } catch (_error) {
        return `${properties.amount} RSD`;
    }
}

function createRsdAmountFormatter() {
    try {
        return new Intl.NumberFormat('sr-RS', {
            style: 'currency',
            currency: 'RSD',
            maximumFractionDigits: 2
        });
    } catch (_error) {
        return null;
    }
}

function formatEndTime(value) {
    if (value === null || value === undefined || value === '') {
        return 'Није наведен';
    }
    const instant = new Date(value);
    if (Number.isNaN(instant.getTime())) {
        return 'Није наведен';
    }
    return new Intl.DateTimeFormat('sr-RS', {
        dateStyle: 'medium',
        timeStyle: 'short',
        timeZone: 'Europe/Belgrade'
    }).format(instant);
}

async function loadMetadata() {
    const sequence = ++state.metadataSequence;
    const [basemap, data] = await Promise.allSettled([
        fetchJson('/api/basemap/status'),
        fetchJson('/api/map/status')
    ]);
    if (sequence !== state.metadataSequence) return;

    if (basemap.status === 'fulfilled' && basemap.value.healthy) {
        elements.basemapVersion.textContent = basemap.value.activeVersion || 'Без ознаке верзије';
        if (basemap.value.warning) {
            state.metadataWarnings.add('Основна карта користи последњу исправну верзију након неуспелог ажурирања.');
        }
    } else {
        elements.basemapVersion.textContent = 'Недоступна';
        state.metadataWarnings.add('Није потврђена активна верзија локалне основне карте.');
    }

    if (data.status === 'fulfilled' && data.value.available) {
        elements.dataVersion.textContent = data.value.dataVersion || 'Без ознаке верзије';
        elements.lastSync.textContent = formatEndTime(data.value.lastSuccessfulSync);
        if (data.value.stale) {
            state.metadataWarnings.add(`Подаци су старији од дозвољеног периода свежине. Последње успешно освежавање карте: ${formatEndTime(data.value.lastSuccessfulSync)}.`);
        }
    } else {
        elements.dataVersion.textContent = 'Нема успешне верзије';
        elements.lastSync.textContent = 'Није забележено';
        state.metadataWarnings.add('Ниједно успешно освежавање података за карту није забележено.');
    }
    renderMetadataWarnings();
}

async function fetchJson(url) {
    const response = await fetch(url, {headers: {'Accept': 'application/json'}, cache: 'no-store'});
    if (!response.ok) {
        throw new Error(`STATUS_HTTP_${response.status}`);
    }
    return response.json();
}

function renderMetadataWarnings() {
    const warnings = new Set([
        ...state.metadataWarnings,
        ...state.resourceWarnings.values()
    ]);
    diagnostics.activeResourceWarnings = state.resourceWarnings.size;
    if (!warnings.size) {
        elements.freshnessWarning.hidden = true;
        elements.freshnessWarning.textContent = '';
        return;
    }
    elements.freshnessWarning.textContent = [...warnings].join(' ');
    elements.freshnessWarning.hidden = false;
}

function setMapState(name, message) {
    diagnostics.lastState = name;
    elements.state.dataset.state = name;
    elements.state.setAttribute('role', name === 'error' ? 'alert' : 'status');
    elements.state.setAttribute('aria-live', name === 'error' ? 'assertive' : 'polite');
    elements.state.dataset.retained = String(state.features.length > 0);
    if (elements.state.textContent !== message) elements.state.textContent = message;
    document.getElementById('map-update-indicator').textContent = name === 'loading' ? 'Освежавање…' : '';
    document.getElementById('map-retry').hidden = true;
    document.getElementById('filter-state').textContent = name === 'error' ? message : '';
}

function handleMapError(event) {
    diagnostics.mapErrors++;
    const sourceId = event?.sourceId;
    if (sourceId === BASEMAP_SOURCE || isBasemapResourceUrl(mapErrorUrl(event))) {
        diagnostics.basemapErrors++;
        state.resourceWarnings.set(
                `source:${BASEMAP_SOURCE}`,
                'Основна карта је пријавила привремени проблем са ресурсом; доступни слојеви и подаци остају приказани.');
    } else if ([POINT_SOURCE, AREA_SOURCE, SELECTION_SOURCE].includes(sourceId)) {
        diagnostics.auctionSourceErrors++;
        state.resourceWarnings.set(
                `source:${sourceId}`,
                'Слој аукција је пријавио привремени проблем; претходно учитани резултати остају приказани.');
    } else {
        state.resourceWarnings.set(
                sourceId ? `source:${sourceId}` : 'resource:map',
                'Карта је пријавила привремени проблем са ресурсом; доступни садржај остаје приказан.');
    }
    renderMetadataWarnings();
}

function handleSourceRecovery(event) {
    if (event?.isSourceLoaded !== true || typeof event.sourceId !== 'string') {
        return;
    }
    if (state.resourceWarnings.delete(`source:${event.sourceId}`)) {
        renderMetadataWarnings();
    }
}

function mapErrorUrl(event) {
    for (const candidate of [event?.error?.url, event?.url]) {
        if (typeof candidate === 'string' && candidate) {
            return candidate;
        }
    }
    return null;
}

function isBasemapResourceUrl(value) {
    if (!value) {
        return false;
    }
    if (value.startsWith('pmtiles:')) {
        return value.includes('/basemap/');
    }
    try {
        const url = new URL(value, document.baseURI);
        return url.origin === window.location.origin && url.pathname.startsWith('/basemap/');
    } catch (_error) {
        return false;
    }
}

function configureAccessibleMap(map) {
    const canvas = map.getCanvas();
    canvas.tabIndex = 0;
    canvas.setAttribute('aria-label',
            'Карта аукција. Користите стрелице за померање, плус и минус за увећање. Enter активира групу или објекат у средини карте; сви учитани објекти су и у листи резултата.');
    const zoomIn = map.getContainer().querySelector('.maplibregl-ctrl-zoom-in');
    const zoomOut = map.getContainer().querySelector('.maplibregl-ctrl-zoom-out');
    zoomIn?.setAttribute('aria-label', 'Увећај карту');
    zoomOut?.setAttribute('aria-label', 'Умањи карту');
}

function mapLoaded(map, {
    timeoutMs = 30_000,
    onRecoverableError = () => {}
} = {}) {
    if (map.loaded()) {
        return Promise.resolve();
    }
    return new Promise((resolve, reject) => {
        let timeout;
        const cleanup = () => {
            window.clearTimeout(timeout);
            map.off('load', handleLoad);
            map.off('error', handleInitialError);
        };
        const handleLoad = () => {
            cleanup();
            resolve();
        };
        const handleInitialError = event => {
            if (!fatalInitialStyleError(map, event)) {
                onRecoverableError(event);
                return;
            }
            cleanup();
            reject(new Error('BASEMAP_STYLE_LOAD_FAILED', {cause: event?.error}));
        };
        timeout = window.setTimeout(() => {
            cleanup();
            reject(new Error('BASEMAP_LOAD_TIMEOUT'));
        }, timeoutMs);
        map.on('load', handleLoad);
        map.on('error', handleInitialError);
    });
}

function fatalInitialStyleError(map, event) {
    if (map.isStyleLoaded?.() === true || event?.tile) {
        return false;
    }
    if (typeof event?.sourceId === 'string') {
        return event.sourceId === BASEMAP_SOURCE;
    }
    const resourceUrl = mapErrorUrl(event);
    if (!resourceUrl) {
        return true;
    }
    try {
        const pathname = new URL(resourceUrl, document.baseURI).pathname;
        return !pathname.startsWith('/basemap/sprites/')
                && !pathname.startsWith('/basemap/glyphs/');
    } catch (_error) {
        return true;
    }
}

function renderedClusterCount() {
    if (!state.map?.getLayer(CLUSTER_LAYER)) {
        return 0;
    }
    return state.map.queryRenderedFeatures({layers: [CLUSTER_LAYER]}).length;
}

function reducedMotion() {
    return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;
}

function errorName(error) {
    if (typeof error?.message === 'string' && /^[A-Z0-9_]+$/.test(error.message)) {
        return error.message;
    }
    return error?.name || 'MAP_ERROR';
}

function pointLayer(precision) {
    return `auction-point-${precision.toLowerCase().replaceAll('_', '-')}`;
}

function areaFillLayer(precision) {
    return `auction-area-${precision.toLowerCase().replaceAll('_', '-')}`;
}

function areaLineLayer(precision) {
    return `${areaFillLayer(precision)}-outline`;
}

function iconName(precision) {
    return `auction-icon-${precision.toLowerCase().replaceAll('_', '-')}`;
}
