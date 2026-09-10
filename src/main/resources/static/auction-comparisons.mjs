import {ComparisonStorage, STORAGE_KEY, VISIT_KEY, validFrame, personalParameters, civilParts} from './comparison-storage.mjs';

export const COMPARISON_FIELDS = ['since', 'sinceAt', 'sinceLocal', 'sinceOffset', 'publication', 'changeKind', 'liveBidding'];
const time = value => value ? new Intl.DateTimeFormat('sr-RS', {dateStyle: 'medium', timeStyle: 'medium', timeZone: 'Europe/Belgrade'}).format(new Date(value)) + ' (Београд)' : 'Нема';
const statuses = {NEVER_REVIEWED: 'Преглед није потврђен', UNCHANGED: 'Прегледано; без значајних промена',
    CHANGED: 'Промењена од прегледа', UNAVAILABLE: 'Поређење прегледа није доступно'};

export function createComparisonUI({form}) {
    const feedback = document.getElementById('comparison-feedback');
    let storage = null, frame = null, evidence = {}, comparisons = {}, comparisonError = false;
    let serial = 0, activation = null, storedSnapshot = null;
    const report = message => { feedback.textContent = message; };
    function init() {
        try { storage = new ComparisonStorage(window.localStorage, window.sessionStorage); }
        catch (e) { report(`Локално складиште није доступно. ${e.message} Обично прегледање и датумски филтери остају доступни.`); }
        // Serialize read-modify-write across tabs, including capacity checks. Never silently risk lost acknowledgements.
        if (!navigator.locks) { storage = null; report('Локално поређење захтева Web Locks (HTTPS или localhost). Датумски филтери остају доступни.'); }
        updateTools();
    }
    async function mutate(action, message) {
        if (!storage) { report('Локално складиште није доступно; потврда није сачувана.'); return false; }
        try {
            await navigator.locks.request(STORAGE_KEY, () => action(storage));
            if (message) report(message);
            updateTools(); updateReviewLabels();
            return true;
        } catch (e) { report(`Није сачувано. ${e.message}`); return false; }
    }
    function data() {
        try { return storage?.read(); }
        catch (e) { storage = null; report(e.message); return null; }
    }
    function updateTools() {
        const saved = data();
        storedSnapshot = saved;
        publicationOption.hidden = sinceControl.value !== 'publication' && !form.elements.namedItem('publication').value;
        document.getElementById('checkpoint-state').textContent = saved?.checkpoint
            ? `Моја тачка: ${time(saved.checkpoint.evaluatedAt)} · На овом прегледачу. Подаци објављени: ${time(saved.checkpoint.publishedAt)}.`
            : 'Нема моје тачке поређења на овом прегледачу. Датумско поређење и обични резултати су доступни.';
        document.getElementById('previous-visit-state').textContent = storage?.previous
            ? `Претходна посета: ${time(storage.previous.evaluatedAt)}. Замрзнуто до краја ове сесије картице; поновно учитавање не помера границу.`
            : 'Нема претходне посете. Први успешан приказ ће бити доступан наредној новој сесији картице, не овој посети.';
        document.getElementById('displayed-publication').textContent = validFrame(frame)
            ? `Приказани изворни подаци објављени: ${time(frame.publishedAt)}. Провера: ${time(frame.evaluatedAt)}.`
            : 'Још нема приказане поуздане изворне публикације. Обичан каталог остаје доступан.';
        document.getElementById('checkpoint-save').disabled = !saved || !validFrame(frame);
        document.getElementById('checkpoint-save').textContent = saved?.checkpoint
            ? 'Замени моју тачку овим приказаним подацима' : 'Користи ове податке као моју тачку поређења';
        document.getElementById('checkpoint-clear').disabled = !saved?.checkpoint;
        document.getElementById('reviewed-toggle').disabled = !saved;
        document.getElementById('reviews-export').disabled = false;
        document.getElementById('reviews-clear').disabled = false;
    }
    function snapshotDraft() { return JSON.stringify(COMPARISON_FIELDS.map(name => [name, form.elements.namedItem(name).value])); }
    function restore(query) {
        publicationOption.hidden = query.get('since') !== 'publication';
        const parts = query.get('since') === 'date' ? civilParts(query.get('sinceAt')) : {local: '', offset: ''};
        for (const name of COMPARISON_FIELDS) form.elements.namedItem(name).value = name === 'sinceLocal' ? parts.local
            : name === 'sinceOffset' ? parts.offset : query.get(name) || '';
    }
    function resolvePersonal() {
        const mode = form.elements.namedItem('since').value;
        if (!['previous', 'checkpoint'].includes(mode)) return true;
        try {
            const baseline = mode === 'previous' ? storage?.previous : data()?.checkpoint;
            const resolved = personalParameters(baseline);
            publicationOption.hidden = false;
            for (const [key, value] of Object.entries(resolved)) form.elements.namedItem(key).value = value;
            form.elements.namedItem('sinceLocal').value = ''; form.elements.namedItem('sinceOffset').value = '';
            return true;
        } catch (e) { report(e.message); return false; }
    }
    const sinceControl = form.elements.namedItem('since');
    const publicationOption = [...sinceControl.options].find(option => option.value === 'publication')
        || new Option('Сачувана тачка поређења', 'publication');
    if (!publicationOption.isConnected) sinceControl.add(publicationOption);
    publicationOption.hidden = sinceControl.value !== 'publication';
    for (const [value, label] of [['previous', 'Претходна посета — на овом прегледачу'], ['checkpoint', 'Моја тачка — на овом прегледачу']]) {
        form.elements.namedItem('since').add(new Option(label, value));
    }
    // Also supports a usable native GET when WebGL fails. Personal names are never submitted.
    form.addEventListener('submit', event => {
        report('');
        if (!resolvePersonal()) { event.preventDefault(); event.stopImmediatePropagation(); }
    }, true);
    document.getElementById('checkpoint-save').addEventListener('click', () => {
        const displayed = structuredClone(frame); // Capture before waiting for another tab/confirmation.
        if (data()?.checkpoint && !confirm('Заменити моју тачку приказаним подацима? Ово не потврђује преглед аукција.')) return;
        mutate(s => s.checkpoint(displayed), 'Тачка поређења је сачувана на овом прегледачу. Ниједна аукција није означена као прегледана.');
    });
    document.getElementById('checkpoint-clear').addEventListener('click', () => {
        if (confirm('Обрисати моју тачку? Примењена веза и потврде прегледа остају непромењене.')) mutate(s => s.checkpoint(null), 'Моја тачка је обрисана.');
    });
    document.getElementById('reviews-export').addEventListener('click', () => {
        try {
            const url = URL.createObjectURL(new Blob([localStorage.getItem(STORAGE_KEY) || '{}'], {type: 'application/json'}));
            const link = document.createElement('a'); link.href = url; link.download = 'eaukcija-local-comparisons.json'; link.click();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
        } catch { report('Складиште није доступно за извоз.'); }
    });
    document.getElementById('reviews-clear').addEventListener('click', async () => {
        if (!confirm(storage ? 'Обрисати све потврде прегледа на овом прегледачу? Тачка и филтери остају.'
            : 'Обрисати неупотребљиво локално складиште поређења, укључујући тачку и претходну посету? Прво извезите копију.')) return;
        if (storage) await mutate(s => s.clearReviews(), 'Потврде прегледа су обрисане.');
        else try { localStorage.removeItem(STORAGE_KEY); sessionStorage.removeItem(VISIT_KEY); init(); }
        catch { report('Брисање складишта је блокирано. Омогућите складиште у прегледачу.'); }
        refreshReviews();
    });
    document.getElementById('reviewed-toggle').addEventListener('click', () => {
        const panel = document.getElementById('reviewed-results'); panel.hidden = !panel.hidden;
        document.getElementById('reviewed-toggle').setAttribute('aria-expanded', String(!panel.hidden));
        if (!panel.hidden) refreshReviews();
    });
    const captureActivation = event => {
        const mark = event.target.closest('.mark-reviewed');
        if (!mark?.dataset.review || event.type === 'keydown' && !['Enter', ' '].includes(event.key)) { activation = null; return; }
        if (event.type === 'keydown' && event.repeat) { event.preventDefault(); return; }
        activation = {review: JSON.parse(mark.dataset.review)};
    };
    document.addEventListener('pointerdown', captureActivation, true);
    document.addEventListener('keydown', captureActivation, true);
    document.addEventListener('pointercancel', () => { activation = null; }, true);
    document.addEventListener('click', async event => {
        const mark = event.target.closest('.mark-reviewed');
        const clear = event.target.closest('.clear-reviewed');
        if (mark?.dataset.review) {
            const current = JSON.parse(mark.dataset.review);
            // A refresh between Space/pointer down and click cannot acknowledge newly displayed content.
            const review = activation?.review.auctionId === current.auctionId ? activation.review : current;
            activation = null;
            const saved = data()?.reviews[String(review.auctionId)];
            if (saved && !confirm(`Поново потврдити преглед приказане ревизије аукције ${review.auctionId}?`)) return;
            if (await mutate(s => s.acknowledge(review), `Преглед аукције ${review.auctionId} је потврђен на овом прегледачу.`)) refreshReviews();
        } else if (clear && confirm(`Обрисати потврду прегледа аукције ${clear.dataset.reviewId}?`)) {
            if (await mutate(s => s.clearReview(clear.dataset.reviewId), 'Потврда је обрисана.')) refreshReviews();
        }
    });
    window.addEventListener('storage', event => {
        if (event.key !== STORAGE_KEY && event.key !== null) return;
        // Another tab never changes this visit's frozen previous baseline or applies drafts.
        updateTools(); updateReviewLabels(); refreshReviews();
    });
    async function loadReviews(upper, signal, live = false) {
        const reviews = Object.values(data()?.reviews || {}).map(value => value.review);
        if (!reviews.length) return {evidence: {}, reviewKey: '[]'};
        if (!validFrame(upper)) return {error: true};
        try {
            const response = await fetch('/api/auctions/reviews', {method: 'POST', cache: 'no-store', signal,
                headers: {'Content-Type': 'application/json', Accept: 'application/json'},
                body: JSON.stringify({frame: upper, reviews, liveBidding: live})});
            if (!response.ok) return {error: true};
            const result = await response.json();
            if (!result.evidence || Array.isArray(result.evidence) || typeof result.evidence !== 'object'
                    || JSON.stringify(result.frame) !== JSON.stringify(upper)
                    || Object.keys(result.evidence).length !== reviews.length
                    || reviews.some(r => {
                        const e = result.evidence[String(r.auctionId)];
                        return String(e?.auctionId) !== String(r.auctionId) || !['UNCHANGED', 'CHANGED', 'UNAVAILABLE'].includes(e?.reviewState);
                    })) return {error: true};
            return {...result, reviewKey: JSON.stringify(reviews)};
        } catch (e) { if (e.name === 'AbortError') throw e; return {error: true}; }
    }
    let appliedLive = false;
    const reviewKey = () => JSON.stringify(Object.values(data()?.reviews || {}).map(value => value.review));
    async function refreshReviews() {
        const ticket = ++serial, displayed = frame;
        const result = await loadReviews(displayed, undefined, appliedLive);
        if (ticket !== serial || frame !== displayed) return;
        if (result.reviewKey && result.reviewKey !== reviewKey()) { refreshReviews(); return; }
        comparisons = result.evidence || {}; comparisonError = !!result.error;
        updateReviewLabels(); renderReviewed();
    }
    function updateReviewLabels(root = document, saved = data()) {
        for (const node of root.querySelectorAll('.review-state')) {
            const id = node.dataset.reviewId;
            const status = saved?.reviews[id] ? comparisons[id]?.reviewState || 'UNAVAILABLE' : 'NEVER_REVIEWED';
            node.textContent = storage ? statuses[status] : 'Локално стање прегледа није доступно';
        }
        for (const node of root.querySelectorAll('.review-reasons')) {
            const id = node.dataset.reviewId, e = comparisons[id];
            const show = saved?.reviews[id] && e?.reviewState !== 'UNCHANGED';
            node.hidden = !show;
            if (show) {
                const reason = !e || e.reviewState === 'UNAVAILABLE' ? 'Докази поређења нису доступни.'
                    : node.dataset.compact === 'true' ? e.reasons || (e.elapsedEnd ? 'Истекао рок завршетка' : 'Животни циклус')
                    : e.explanation;
                node.textContent = `Од прегледа: ${reason}`;
            }
        }
        for (const button of root.querySelectorAll('.mark-reviewed')) button.disabled = !saved;
        for (const button of root.querySelectorAll('.clear-reviewed')) button.disabled = !saved?.reviews[button.dataset.reviewId];
    }
    function decorate(container, id, supplied = evidence[String(id)], {compact = false} = {}) {
        if (!supplied) { container.querySelector(':scope > .auction-change')?.remove(); return; }
        let host = container.querySelector(':scope > .auction-change');
        if (!host) { host = document.createElement('div'); host.className = 'auction-change'; container.append(host); }
        const focusClass = host.contains(document.activeElement) ? document.activeElement.className : null;
        host.replaceChildren();
        if (supplied.badge) { const badge = document.createElement('strong'); badge.className = 'change-badge'; badge.textContent = supplied.badge; host.append(badge); }
        const reason = compact ? supplied.reasons : supplied.explanation;
        if (reason) { const note = document.createElement('p'); note.textContent = reason; host.append(note); }
        const state = document.createElement('span'); state.className = 'review-state'; state.dataset.reviewId = String(id); host.append(state);
        if (supplied !== comparisons[String(id)]) {
            const ownReasons = document.createElement('p'); ownReasons.className = 'review-reasons';
            ownReasons.dataset.reviewId = String(id); ownReasons.dataset.compact = String(compact); host.append(ownReasons);
        }
        if (supplied.review && !compact) {
            const mark = document.createElement('button'); mark.type = 'button'; mark.className = 'mark-reviewed';
            mark.textContent = 'Потврди преглед'; mark.setAttribute('aria-label', `Потврди преглед аукције ${id}`);
            mark.dataset.review = JSON.stringify(supplied.review); host.append(mark);
        }
        if (!compact) {
            const clear = document.createElement('button'); clear.type = 'button'; clear.className = 'clear-reviewed';
            clear.dataset.reviewId = String(id); clear.textContent = 'Обриши потврду';
            clear.setAttribute('aria-label', `Обриши потврду прегледа аукције ${id}`); host.append(clear);
        }
        updateReviewLabels(host, storedSnapshot);
        if (focusClass) [...host.querySelectorAll('button')].find(node => node.className === focusClass)?.focus({preventScroll: true});
    }
    function renderReviewed() {
        const list = document.getElementById('reviewed-list');
        const focused = list.contains(document.activeElement) ? document.activeElement : null;
        const focusedId = focused?.closest('li[data-auction-id]')?.dataset.auctionId;
        const action = focused?.matches('.mark-reviewed, .clear-reviewed') ? focused.className : null;
        list.replaceChildren();
        const values = Object.values(comparisons);
        const changed = values.filter(e => e.reviewState === 'CHANGED' || e.reviewState === 'UNAVAILABLE');
        document.getElementById('reviewed-status').textContent = comparisonError
            ? 'Поређење није доступно. Потврде су задржане; ово НЕ значи да нема промена. Покушајте поново или обришите неважећу потврду.'
            : !Object.keys(data()?.reviews || {}).length ? 'Нема изричитих потврда прегледа на овом прегледачу.'
            : `${changed.length} промењених / недоступних; ${values.length - changed.length} без значајних промена. Провера: ${time(frame?.evaluatedAt)}.`;
        for (const e of changed) {
            const item = document.createElement('li'); item.dataset.auctionId = String(e.auctionId);
            const title = document.createElement('h3'); title.textContent = `Аукција ${e.auctionId} — ${statuses[e.reviewState]}`;
            item.append(title);
            const link = document.createElement('a'); link.href = `https://eaukcija.sud.rs/#/aukcije/${e.auctionId}`;
            link.textContent = 'Отвори на порталу еАукција'; link.target = '_blank'; link.rel = 'noopener noreferrer'; item.append(link);
            if (e.endAt) { const end = document.createElement('p'); end.textContent = `Задржани рок завршетка: ${time(e.endAt)}`; item.append(end); }
            decorate(item, e.auctionId, e); list.append(item);
        }
        updateReviewLabels();
        if (focused) {
            const target = action && list.querySelector(`li[data-auction-id="${focusedId}"] .${action}`);
            (target || document.getElementById('reviewed-status')).focus({preventScroll: true});
        }
    }
    function accept(upper, currentEvidence = {}, result = {evidence: {}}, live = false) {
        ++serial; frame = upper; evidence = currentEvidence; appliedLive = live;
        const outdatedReviews = result.reviewKey && result.reviewKey !== reviewKey();
        comparisons = outdatedReviews ? {} : result.evidence || {}; comparisonError = !!result.error || !!outdatedReviews;
        if (outdatedReviews) refreshReviews();
        document.getElementById('map-comparison-summary').textContent = document.getElementById('comparison-summary')?.textContent.trim() || '';
        if (storage && validFrame(frame) && !document.hidden) mutate(s => s.displayed(structuredClone(upper)));
        updateTools(); updateReviewLabels(); renderReviewed();
    }
    init();
    let fallbackFrame = null;
    try { fallbackFrame = JSON.parse(document.getElementById('shared-results').dataset.sourceFrame); } catch { /* Never substitute now. */ }
    const showFallback = () => {
        if (!frame && fallbackFrame && document.getElementById('table-view').getClientRects().length) {
            accept(fallbackFrame); refreshReviews();
        }
    };
    showFallback();
    window.addEventListener('eaukcija:workspace-mode', showFallback);
    document.addEventListener('visibilitychange', () => {
        if (!document.hidden && validFrame(frame)) mutate(s => s.displayed(structuredClone(frame)));
    });
    return {accept, loadReviews, decorate, snapshotDraft, restore, resolvePersonal,
        stale: () => { document.getElementById('reviewed-status').textContent = 'Освежавање није успело; претходна провера није доказ да сте све видели.'; }};
}
