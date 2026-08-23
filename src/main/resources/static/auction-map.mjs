import {NavigationControl, Popup} from './vendor/maplibre-gl/6.1.0/maplibre-gl.mjs';
import {createLocalBasemap} from './basemap-map.mjs';

const POINT_SOURCE = 'auction-points';
const AREA_SOURCE = 'auction-areas';
const CLUSTER_LAYER = 'auction-clusters';
const CLUSTER_COUNT_LAYER = 'auction-cluster-count';
const SELECTED_POINT_LAYER = 'auction-selected-point';
const SELECTED_AREA_LAYER = 'auction-selected-area';
const LOAD_DEBOUNCE_MS = 250;
const RESULT_LIMIT = 1000;
const EMPTY_COLLECTION = Object.freeze({type: 'FeatureCollection', features: []});

const PRECISIONS = Object.freeze({
    PARCEL: {
        label: 'Парцела',
        explanation: 'Проверена граница или тачка парцеле.',
        color: '#7b2cbf',
        shape: 'diamond',
        dash: [10, 1]
    },
    ADDRESS: {
        label: 'Адреса',
        explanation: 'Адресна тачка; не мора представљати обрис објекта или парцеле.',
        color: '#006d77',
        shape: 'square',
        dash: [3, 1]
    },
    STREET: {
        label: 'Улица',
        explanation: 'Приближна тачка улице, без тврдње о тачној адреси.',
        color: '#b45309',
        shape: 'triangle',
        dash: [1, 1]
    },
    CADASTRAL_MUNICIPALITY: {
        label: 'Центар катастарске општине',
        explanation: 'Центар катастарске општине; ово није адреса ни парцела.',
        color: '#0057b8',
        shape: 'hexagon',
        dash: [4, 2]
    },
    SETTLEMENT: {
        label: 'Центар насеља',
        explanation: 'Центар насеља; стварна непокретност може бити удаљена.',
        color: '#9c2f58',
        shape: 'circle',
        dash: [2, 2]
    },
    MUNICIPALITY: {
        label: 'Центар општине',
        explanation: 'Најшири приближни приказ, у центру општине.',
        color: '#4b5563',
        shape: 'cross',
        dash: [1, 2]
    }
});

const URL_FIELDS = Object.freeze([
    {element: 'map-status-filter', parameter: 'mapStatus', api: 'status', type: 'select'},
    {element: 'map-kind-filter', parameter: 'mapKind', api: 'kind', type: 'select'},
    {element: 'map-precision-filter', parameter: 'mapPrecision', api: 'precision', type: 'select'},
    {element: 'map-from-filter', parameter: 'mapFrom', api: 'from', type: 'date'},
    {element: 'map-to-filter', parameter: 'mapTo', api: 'to', type: 'date'}
]);

const elements = {
    filterForm: document.getElementById('map-filters'),
    filterReset: document.getElementById('map-filter-reset'),
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

const diagnostics = {
    requestsStarted: 0,
    requestsCompleted: 0,
    requestsAborted: 0,
    lastFeatureCount: 0,
    truncated: false,
    lastState: 'loading',
    lastError: null,
    precisionStyles: Object.keys(PRECISIONS),
    selectedAuctionId: null
};

const state = {
    map: null,
    popup: null,
    features: [],
    selectedAuctionId: readSelectedAuction(),
    debounceTimer: null,
    activeRequest: null,
    requestSequence: 0,
    metadataWarnings: new Set()
};

const publicApi = {
    ready: false,
    map: null,
    refreshNow: () => refreshNow(),
    getDiagnostics: () => ({...diagnostics}),
    renderedClusterCount: () => renderedClusterCount()
};
window.__auctionMap = publicApi;

restoreFiltersFromUrl();
bindFilterControls();
initialize();

async function initialize() {
    const metadata = loadMetadata();
    try {
        state.map = await createLocalBasemap({
            container: 'auction-map',
            center: [20.46, 44.79],
            zoom: 14,
            minZoom: 5,
            maxZoom: 20,
            fadeDuration: reducedMotion() ? 0 : 150
        });
        publicApi.map = state.map;
        state.map.addControl(new NavigationControl({showCompass: false}), 'top-right');
        await mapLoaded(state.map);
        configureAccessibleMap(state.map);
        addAuctionSourcesAndLayers(state.map);
        bindMapInteractions(state.map);
        state.map.on('moveend', () => scheduleLoad());
        await loadViewport();
    } catch (error) {
        diagnostics.lastError = errorName(error);
        setMapState(
                'error',
                'Карта тренутно није доступна. Проверите локални пакет основне карте и покушајте поново.');
    } finally {
        await metadata;
        publicApi.ready = true;
    }
}

function bindFilterControls() {
    elements.filterForm.addEventListener('submit', event => {
        event.preventDefault();
        if (!validDateRange()) {
            return;
        }
        state.selectedAuctionId = null;
        diagnostics.selectedAuctionId = null;
        closePopup();
        writeUrlState();
        refreshNow();
    });

    elements.filterReset.addEventListener('click', () => {
        for (const field of URL_FIELDS) {
            document.getElementById(field.element).value = '';
        }
        state.selectedAuctionId = null;
        diagnostics.selectedAuctionId = null;
        closePopup();
        writeUrlState();
        refreshNow();
    });
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

function restoreFiltersFromUrl() {
    const parameters = new URL(window.location.href).searchParams;
    let sanitized = false;
    for (const field of URL_FIELDS) {
        const element = document.getElementById(field.element);
        const candidate = parameters.get(field.parameter);
        if (!candidate) {
            continue;
        }
        if (field.type === 'select' && [...element.options].some(option => option.value === candidate)) {
            element.value = candidate;
        } else if (field.type === 'date' && validIsoDate(candidate)) {
            element.value = candidate;
        } else {
            parameters.delete(field.parameter);
            sanitized = true;
        }
    }
    const selected = parameters.get('auction');
    if (selected && !validAuctionId(selected)) {
        parameters.delete('auction');
        state.selectedAuctionId = null;
        sanitized = true;
    }
    if (sanitized) {
        replaceUrl(parameters);
    }
}

function writeUrlState() {
    const url = new URL(window.location.href);
    for (const field of URL_FIELDS) {
        const value = document.getElementById(field.element).value;
        if (value) {
            url.searchParams.set(field.parameter, value);
        } else {
            url.searchParams.delete(field.parameter);
        }
    }
    if (state.selectedAuctionId && validAuctionId(state.selectedAuctionId)) {
        url.searchParams.set('auction', state.selectedAuctionId);
    } else {
        url.searchParams.delete('auction');
    }
    replaceUrl(url.searchParams);
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

function validIsoDate(value) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
        return false;
    }
    const date = new Date(`${value}T00:00:00Z`);
    return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

function scheduleLoad(delay = LOAD_DEBOUNCE_MS) {
    window.clearTimeout(state.debounceTimer);
    abortActiveRequest();
    setMapState(
            'loading',
            state.features.length
                    ? 'Освежавање видљивог дела карте; претходни резултати остају приказани…'
                    : 'Учитавање аукција у видљивом делу карте…');
    state.debounceTimer = window.setTimeout(() => {
        state.debounceTimer = null;
        loadViewport();
    }, delay);
}

async function refreshNow() {
    window.clearTimeout(state.debounceTimer);
    state.debounceTimer = null;
    abortActiveRequest();
    if (!state.map?.isStyleLoaded()) {
        return;
    }
    return loadViewport();
}

async function loadViewport() {
    if (!state.map) {
        return;
    }
    abortActiveRequest();
    const controller = new AbortController();
    state.activeRequest = controller;
    const sequence = ++state.requestSequence;
    diagnostics.requestsStarted++;
    diagnostics.lastError = null;
    setMapState(
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
            throw new Error(`MAP_HTTP_${response.status}`);
        }
        const collection = validateCollection(await response.json());
        if (sequence !== state.requestSequence) {
            return;
        }
        state.features = collection.features;
        diagnostics.requestsCompleted++;
        diagnostics.lastFeatureCount = collection.features.length;
        diagnostics.truncated = collection.truncated === true;
        updateSources(collection.features);
        renderResults(collection.features);
        elements.limitWarning.hidden = !collection.truncated;

        if (collection.features.length === 0) {
            setMapState('empty', 'У видљивом делу карте нема аукција за изабране филтере.');
        } else {
            const suffix = collection.truncated
                    ? ' Приказ је ограничен; сузите област или филтере.'
                    : '';
            setMapState(
                    'ready',
                    `Приказано аукција: ${collection.features.length}.${suffix}`);
        }
        restoreSelectionFromFeatures();
    } catch (error) {
        if (error?.name === 'AbortError') {
            return;
        }
        diagnostics.lastError = errorName(error);
        const retained = state.features.length
                ? ` Претходних ${state.features.length} резултата остаје приказано.`
                : '';
        setMapState(
                'error',
                `Није могуће преузети аукције за овај приказ.${retained} Покушајте поново.`);
    } finally {
        if (state.activeRequest === controller) {
            state.activeRequest = null;
        }
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
}

function viewportUrl() {
    const bounds = state.map.getBounds();
    const query = new URLSearchParams();
    query.set('bbox', [
        bounds.getWest(), bounds.getSouth(), bounds.getEast(), bounds.getNorth()
    ].map(coordinate => coordinate.toFixed(6)).join(','));
    query.set('limit', String(RESULT_LIMIT));
    for (const field of URL_FIELDS) {
        const value = document.getElementById(field.element).value;
        if (value) {
            query.set(field.api, value);
        }
    }
    return `/api/map/auctions?${query.toString()}`;
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

function validFeature(feature) {
    const properties = feature?.properties;
    return feature?.type === 'Feature'
            && typeof feature.id === 'string'
            && ['Point', 'Polygon', 'MultiPolygon'].includes(feature?.geometry?.type)
            && Array.isArray(feature?.geometry?.coordinates)
            && properties
            && validAuctionId(String(properties.auctionId))
            && typeof properties.title === 'string'
            && Object.hasOwn(PRECISIONS, properties.precision)
            && typeof properties.detailUrl === 'string';
}

function updateSources(features) {
    const points = features.filter(feature => feature.geometry.type === 'Point');
    const areas = features.filter(feature => feature.geometry.type !== 'Point');
    state.map.getSource(POINT_SOURCE).setData({type: 'FeatureCollection', features: points});
    state.map.getSource(AREA_SOURCE).setData({type: 'FeatureCollection', features: areas});
    updateSelectionLayers();
}

function addAuctionSourcesAndLayers(map) {
    for (const [precision, presentation] of Object.entries(PRECISIONS)) {
        map.addImage(iconName(precision), markerImage(presentation), {pixelRatio: 2});
    }

    map.addSource(POINT_SOURCE, {
        type: 'geojson',
        data: EMPTY_COLLECTION,
        cluster: true,
        clusterMaxZoom: 20,
        clusterRadius: 52
    });
    map.addSource(AREA_SOURCE, {type: 'geojson', data: EMPTY_COLLECTION});

    map.addLayer({
        id: SELECTED_AREA_LAYER,
        type: 'line',
        source: AREA_SOURCE,
        filter: ['==', ['get', 'auctionId'], -1],
        paint: {'line-color': '#111827', 'line-width': 6, 'line-opacity': .75}
    });

    for (const [precision, presentation] of Object.entries(PRECISIONS)) {
        map.addLayer({
            id: areaFillLayer(precision),
            type: 'fill',
            source: AREA_SOURCE,
            filter: ['==', ['get', 'precision'], precision],
            paint: {'fill-color': presentation.color, 'fill-opacity': .25}
        });
        map.addLayer({
            id: areaLineLayer(precision),
            type: 'line',
            source: AREA_SOURCE,
            filter: ['==', ['get', 'precision'], precision],
            paint: {
                'line-color': presentation.color,
                'line-width': 3,
                'line-dasharray': presentation.dash
            }
        });
    }

    map.addLayer({
        id: SELECTED_POINT_LAYER,
        type: 'circle',
        source: POINT_SOURCE,
        filter: ['all', ['!', ['has', 'point_count']], ['==', ['get', 'auctionId'], -1]],
        paint: {
            'circle-radius': 15,
            'circle-color': '#fff',
            'circle-stroke-color': '#111827',
            'circle-stroke-width': 3
        }
    });

    for (const precision of Object.keys(PRECISIONS)) {
        map.addLayer({
            id: pointLayer(precision),
            type: 'symbol',
            source: POINT_SOURCE,
            filter: ['all', ['!', ['has', 'point_count']], ['==', ['get', 'precision'], precision]],
            layout: {
                'icon-image': iconName(precision),
                'icon-size': 1,
                'icon-allow-overlap': false,
                'icon-ignore-placement': false
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
            'text-size': 13
        },
        paint: {'text-color': '#fff'}
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
    map.on('click', CLUSTER_LAYER, event => showCluster(event.features?.[0]));
    setPointerCursor(map, CLUSTER_LAYER);

    for (const precision of Object.keys(PRECISIONS)) {
        for (const layer of [pointLayer(precision), areaFillLayer(precision)]) {
            map.on('click', layer, event => {
                const feature = event.features?.[0];
                if (feature) {
                    selectFeature(feature);
                }
            });
            setPointerCursor(map, layer);
        }
    }
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
    const source = state.map.getSource(POINT_SOURCE);
    const clusterId = Number(cluster.properties.cluster_id);
    const count = Number(cluster.properties.point_count);
    const leaves = await source.getClusterLeaves(clusterId, Math.min(count, RESULT_LIMIT), 0);
    renderClusterSelection(leaves, count);
}

function renderClusterSelection(features, total) {
    elements.selection.replaceChildren();
    const heading = document.createElement('h4');
    heading.textContent = `${total} аукција на овој локацији`;
    elements.selection.append(heading);
    for (const feature of features) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'map-selection-button';
        button.textContent = `${feature.properties.title} — ${precisionLabel(feature)}`;
        button.addEventListener('click', () => selectFeature(feature, {focusSelection: true}));
        elements.selection.append(button);
    }
    if (features.length < total) {
        const note = document.createElement('p');
        note.textContent = `Приказано ${features.length} од ${total}; сузите приказ карте за остале.`;
        elements.selection.append(note);
    }
    elements.selection.hidden = false;
    elements.selection.focus({preventScroll: true});
}

function renderResults(features) {
    elements.resultList.replaceChildren();
    elements.resultCount.textContent = String(features.length);
    for (const feature of features) {
        const item = document.createElement('li');
        item.dataset.precision = feature.properties.precision;
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'map-result-button';
        button.dataset.auctionId = String(feature.properties.auctionId);
        button.setAttribute('aria-current',
                String(feature.properties.auctionId) === state.selectedAuctionId ? 'true' : 'false');

        const title = document.createElement('span');
        title.className = 'map-result-title';
        title.textContent = feature.properties.title;
        const meta = document.createElement('span');
        meta.className = 'map-result-meta';
        meta.textContent = `${precisionLabel(feature)} · ${formatAmount(feature.properties)}`;
        button.append(title, meta);
        button.addEventListener('click', () => selectFeature(feature, {moveMap: true}));
        item.append(button);
        elements.resultList.append(item);
    }
}

function selectFeature(feature, options = {}) {
    const auctionId = String(feature.properties.auctionId);
    if (!validAuctionId(auctionId)) {
        return;
    }
    state.selectedAuctionId = auctionId;
    diagnostics.selectedAuctionId = auctionId;
    writeUrlState();
    updateSelectionLayers();
    updateResultSelection();
    renderSelectedSummary(feature);
    showPopup(feature);
    if (options.moveMap) {
        state.map.easeTo({
            center: representativeCoordinate(feature.geometry),
            duration: reducedMotion() ? 0 : 300
        });
    }
    if (options.focusSelection) {
        elements.selection.focus({preventScroll: true});
    }
}

function renderSelectedSummary(feature) {
    elements.selection.replaceChildren();
    const heading = document.createElement('h4');
    heading.textContent = 'Изабрана аукција';
    const summary = document.createElement('p');
    summary.textContent = `${feature.properties.title} — ${precisionLabel(feature)}. ${precisionExplanation(feature)}`;
    elements.selection.append(heading, summary);
    elements.selection.hidden = false;
}

function updateResultSelection() {
    for (const button of elements.resultList.querySelectorAll('.map-result-button')) {
        button.setAttribute(
                'aria-current',
                button.dataset.auctionId === state.selectedAuctionId ? 'true' : 'false');
    }
}

function updateSelectionLayers() {
    if (!state.map?.getLayer(SELECTED_POINT_LAYER)) {
        return;
    }
    const selected = state.selectedAuctionId ? Number(state.selectedAuctionId) : -1;
    state.map.setFilter(
            SELECTED_POINT_LAYER,
            ['all', ['!', ['has', 'point_count']], ['==', ['get', 'auctionId'], selected]]);
    state.map.setFilter(SELECTED_AREA_LAYER, ['==', ['get', 'auctionId'], selected]);
}

function restoreSelectionFromFeatures() {
    diagnostics.selectedAuctionId = state.selectedAuctionId;
    updateResultSelection();
    if (!state.selectedAuctionId) {
        elements.selection.hidden = true;
        closePopup();
        return;
    }
    const selected = state.features.find(
            feature => String(feature.properties.auctionId) === state.selectedAuctionId);
    if (selected) {
        renderSelectedSummary(selected);
        showPopup(selected);
    } else {
        elements.selection.replaceChildren();
        const text = document.createElement('p');
        text.textContent = 'Изабрана аукција није у тренутно видљивом делу карте или не одговара филтерима.';
        elements.selection.append(text);
        elements.selection.hidden = false;
        closePopup();
    }
}

function showPopup(feature) {
    closePopup();
    const popupContent = document.createElement('article');
    popupContent.className = 'map-popup';
    popupContent.setAttribute('aria-label', `Детаљи аукције ${feature.properties.title}`);

    const title = document.createElement('h3');
    title.textContent = feature.properties.title;
    popupContent.append(title);

    const details = document.createElement('dl');
    appendDetail(details, 'Цена', formatAmount(feature.properties));
    appendDetail(details, 'Завршетак', formatEndTime(feature.properties.endTime));
    appendDetail(details, 'Статус', feature.properties.sourceStatus || 'Није наведен');
    appendDetail(details, 'Прецизност', precisionLabel(feature));
    popupContent.append(details);

    const explanation = document.createElement('p');
    explanation.textContent = precisionExplanation(feature);
    popupContent.append(explanation);

    const sourceUrl = allowlistedSourceUrl(feature.properties.detailUrl, feature.properties.auctionId);
    if (sourceUrl) {
        const sourceLink = document.createElement('a');
        sourceLink.href = sourceUrl;
        sourceLink.target = '_blank';
        sourceLink.rel = 'noopener noreferrer';
        sourceLink.textContent = 'Отвори на порталу еАукција';
        popupContent.append(sourceLink);
    }

    state.popup = new Popup({closeButton: true, closeOnClick: false, maxWidth: '310px'})
            .setLngLat(representativeCoordinate(feature.geometry))
            .setDOMContent(popupContent)
            .addTo(state.map);
}

function appendDetail(list, termText, valueText) {
    const term = document.createElement('dt');
    term.textContent = termText;
    const value = document.createElement('dd');
    value.textContent = valueText;
    list.append(term, value);
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
    state.popup?.remove();
    state.popup = null;
}

function representativeCoordinate(geometry) {
    if (geometry.type === 'Point') {
        return geometry.coordinates;
    }
    const coordinates = [];
    collectCoordinatePairs(geometry.coordinates, coordinates);
    if (!coordinates.length) {
        return [20.46, 44.79];
    }
    const bounds = coordinates.reduce((value, coordinate) => ({
        minX: Math.min(value.minX, coordinate[0]),
        minY: Math.min(value.minY, coordinate[1]),
        maxX: Math.max(value.maxX, coordinate[0]),
        maxY: Math.max(value.maxY, coordinate[1])
    }), {minX: Infinity, minY: Infinity, maxX: -Infinity, maxY: -Infinity});
    return [(bounds.minX + bounds.maxX) / 2, (bounds.minY + bounds.maxY) / 2];
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
    return PRECISIONS[feature.properties.precision]?.label || 'Непозната прецизност';
}

function precisionExplanation(feature) {
    return PRECISIONS[feature.properties.precision]?.explanation
            || 'Прецизност локације није позната.';
}

function formatAmount(properties) {
    if (properties.amount === null || properties.amount === undefined) {
        return 'Цена није наведена';
    }
    try {
        return new Intl.NumberFormat('sr-RS', {
            style: 'currency',
            currency: properties.currency === 'RSD' ? 'RSD' : 'RSD',
            maximumFractionDigits: 2
        }).format(properties.amount);
    } catch (_error) {
        return `${properties.amount} RSD`;
    }
}

function formatEndTime(value) {
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
    const [basemap, data] = await Promise.allSettled([
        fetchJson('/api/basemap/status'),
        fetchJson('/api/map/status')
    ]);

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
            state.metadataWarnings.add('Подаци су старији од дозвољеног периода свежине. Време последњег успешног освежавања остаје приказано.');
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
    if (!state.metadataWarnings.size) {
        elements.freshnessWarning.hidden = true;
        elements.freshnessWarning.textContent = '';
        return;
    }
    elements.freshnessWarning.textContent = [...state.metadataWarnings].join(' ');
    elements.freshnessWarning.hidden = false;
}

function setMapState(name, message) {
    diagnostics.lastState = name;
    elements.state.dataset.state = name;
    elements.state.setAttribute('role', name === 'error' ? 'alert' : 'status');
    elements.state.textContent = message;
}

function configureAccessibleMap(map) {
    const canvas = map.getCanvas();
    canvas.tabIndex = 0;
    canvas.setAttribute('aria-label',
            'Карта аукција. Користите стрелице за померање, плус и минус за увећање.');
    const zoomIn = map.getContainer().querySelector('.maplibregl-ctrl-zoom-in');
    const zoomOut = map.getContainer().querySelector('.maplibregl-ctrl-zoom-out');
    zoomIn?.setAttribute('aria-label', 'Увећај карту');
    zoomOut?.setAttribute('aria-label', 'Умањи карту');
}

function mapLoaded(map) {
    if (map.loaded()) {
        return Promise.resolve();
    }
    return new Promise((resolve, reject) => {
        const timeout = window.setTimeout(
                () => reject(new Error('BASEMAP_LOAD_TIMEOUT')),
                30_000);
        map.once('load', () => {
            window.clearTimeout(timeout);
            resolve();
        });
        map.once('error', event => {
            if (!map.loaded()) {
                window.clearTimeout(timeout);
                reject(event.error || new Error('BASEMAP_LOAD_ERROR'));
            }
        });
    });
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
