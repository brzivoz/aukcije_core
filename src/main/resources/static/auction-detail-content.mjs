import {setText} from './auction-presentation.mjs';

/** A bounded on-demand projection inside the existing selection view, not another selection state. */
export function createLocalDetailContent({formatAmount, formatEndTime}) {
    const container = document.createElement('section');
    container.className = 'local-auction-details';
    const state = document.createElement('p');
    state.setAttribute('role', 'status');
    const retry = document.createElement('button');
    retry.type = 'button'; retry.textContent = 'Поново учитај опис'; retry.hidden = true;
    const metadata = document.createElement('dl');
    const values = {};
    for (const [key, label] of Object.entries({estimatedPrice: 'Процена аукције', startTime: 'Почетак (Београд)',
        publicationTime: 'Објављено (Београд)', firstSale: 'Прва продаја'})) {
        const dt = document.createElement('dt'), dd = document.createElement('dd');
        dt.textContent = label; metadata.append(dt, dd); values[key] = dd;
    }
    const description = document.createElement('p'), shortDescription = document.createElement('p');
    for (const p of [description, shortDescription]) p.className = 'auction-description';
    const heading = document.createElement('h4'), shortHeading = document.createElement('h4');
    heading.textContent = 'Опис из локалног каталога'; shortHeading.textContent = 'Кратак опис огласа';
    container.append(state, retry, metadata, heading, description, shortHeading, shortDescription);
    let request = null, key = null, lastArgs = null, currentAuction = null;

    async function load(args) {
        lastArgs = args;
        const {auctionId, revision, accept} = args;
        const nextKey = `${auctionId}:${revision}`;
        if (key === nextKey) return;
        key = nextKey;
        request?.abort();
        const controller = new AbortController(); request = controller;
        if (currentAuction !== auctionId) {
            currentAuction = auctionId;
            metadata.hidden = true;
            setText(description, ''); setText(shortDescription, '');
            setText(state, 'Учитавање локалног описа…');
        }
        retry.hidden = true;
        try {
            const response = await fetch(`/api/auctions/${auctionId}/details`, {
                cache: 'no-store', signal: controller.signal, headers: {Accept: 'application/json'}
            });
            if (!response.ok) throw new Error(response.status === 404 ? 'NOT_FOUND' : 'UNAVAILABLE');
            const detail = await response.json();
            if (String(detail.auctionId) !== auctionId || typeof detail.category !== 'string'
                    || ['description', 'shortDescription'].some(field => detail[field] != null && typeof detail[field] !== 'string')) throw new Error('INVALID_DETAIL');
            if (request !== controller || controller.signal.aborted || !container.isConnected) return;
            accept(detail);
            setText(values.estimatedPrice, formatAmount({amount: detail.estimatedPrice}));
            setText(values.startTime, formatEndTime(detail.startTime));
            setText(values.publicationTime, formatEndTime(detail.publicationTime));
            setText(values.firstSale, detail.firstSale ? 'Да' : 'Не');
            setText(description, detail.description || 'Пун опис није наведен у локалном каталогу.');
            setText(shortDescription, detail.shortDescription || 'Кратак опис није наведен.');
            metadata.hidden = false;
            setText(state, 'Задржани подаци нису потврда тренутне доступности. Обим продаје проверите на порталу еАукција.');
        } catch (error) {
            if (controller.signal.aborted || request !== controller) return;
            setText(state, error.message === 'NOT_FOUND' ? 'Аукција није пронађена у локалном каталогу.'
                : 'Опис тренутно није доступан. Претходно учитан опис, ако постоји, није освежен.');
            retry.hidden = false;
        }
    }
    retry.addEventListener('click', () => { key = null; load(lastArgs); });
    return {container, load, abort: () => { request?.abort(); request = null; }};
}
