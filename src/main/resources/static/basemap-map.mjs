import {Map, addProtocol} from './vendor/maplibre-gl/6.1.0/maplibre-gl.mjs';

let protocolRegistered = false;

function sameOriginBasemapRoot(baseUrl) {
    const root = new URL(baseUrl ?? '/basemap/', document.baseURI);
    if (root.origin !== window.location.origin) {
        throw new Error('Basemap root must use the application origin');
    }
    return root;
}

function configureStyle(style, root) {
    const source = style?.sources?.serbia;
    if (style?.version !== 8
        || style.sprite !== '/basemap/sprites/light'
        || style.glyphs !== '/basemap/glyphs/{fontstack}/{range}.pbf'
        || source?.url !== 'pmtiles:///basemap/serbia.pmtiles') {
        throw new Error('Basemap style does not match the reviewed local asset contract');
    }

    // MapLibre GL JS 6 requires an absolute sprite URL after a parsed style is
    // supplied. Resolve every root-relative artifact against this page's own
    // origin; the retained style stays deployment-portable.
    style.sprite = new URL('sprites/light', root).href;
    // URL serialisation percent-encodes braces, while MapLibre requires these
    // two literal template tokens. The root itself has already been parsed and
    // origin-checked above.
    style.glyphs = `${root.href}glyphs/{fontstack}/{range}.pbf`;
    source.url = `pmtiles://${new URL('serbia.pmtiles', root).href}`;
    return style;
}

function registerPmtilesProtocol() {
    if (protocolRegistered) {
        return;
    }
    const Protocol = globalThis.pmtiles?.Protocol;
    if (!Protocol) {
        throw new Error('The pinned same-origin PMTiles library was not loaded');
    }
    const protocol = new Protocol();
    addProtocol('pmtiles', protocol.tile);
    protocolRegistered = true;
}

/**
 * Build a plain basemap MapLibre instance. Auction layers and product UX remain
 * #27's responsibility; this is the reusable #25 same-origin asset boundary.
 */
export async function createLocalBasemap({
    container,
    center = [20.4573, 44.7872],
    zoom = 7,
    baseUrl,
    ...options
}) {
    registerPmtilesProtocol();
    const root = sameOriginBasemapRoot(baseUrl);
    const response = await fetch(new URL('style.json', root), {
        headers: {'Accept': 'application/json'},
        cache: 'no-cache'
    });
    if (!response.ok) {
        throw new Error(`Local basemap style request failed with ${response.status}`);
    }
    const style = configureStyle(await response.json(), root);
    return new Map({
        ...options,
        container,
        style,
        center,
        zoom,
        attributionControl: {compact: false}
    });
}
