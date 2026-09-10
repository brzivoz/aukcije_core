/** Browser-only evidence. No query criteria, selected identity or shared-row mutation lives here. */
export const STORAGE_KEY = 'eaukcija.comparisons.v1';
export const VISIT_KEY = 'eaukcija.comparison-visit.v1';
export const MAX_REVIEWS = 200;
export const MAX_STORAGE_BYTES = 256 * 1024;
const empty = () => ({version: 1, checkpoint: null, lastDisplay: null, reviews: {}});
export function validFrame(frame) {
    const p = frame?.publication;
    return p && typeof p.lineage === 'string' && Number.isSafeInteger(p.sequence) && p.sequence > 0
        && typeof p.runId === 'string' && Number.isFinite(Date.parse(frame.evaluatedAt))
        && Number.isFinite(Date.parse(frame.publishedAt));
}
export function referenceText(p) { return `${p.lineage}:${p.sequence}:${p.runId ?? 'origin'}`; }
export function personalParameters(frame) {
    if (!validFrame(frame)) throw new Error('Нема доступне тачке поређења. Изаберите „Било када” или датум.');
    return {since: 'publication', sinceAt: frame.evaluatedAt, publication: referenceText(frame.publication)};
}
export class ComparisonStorage {
    constructor(local, session) {
        this.local = local;
        const data = this.read();
        const visit = session.getItem(VISIT_KEY);
        if (visit) {
            const stored = JSON.parse(visit);
            if (stored.version !== 1 || stored.previous !== null && !validFrame(stored.previous)) throw new Error('Неисправна претходна посета. Обришите локалне податке поређења.');
            this.previous = stored.previous;
        } else {
            this.previous = data.lastDisplay;
            // Persist immediately, including absence. Reload never advances the lower boundary.
            session.setItem(VISIT_KEY, JSON.stringify({version: 1, previous: this.previous}));
        }
    }
    read() {
        const raw = this.local.getItem(STORAGE_KEY);
        if (!raw) return empty();
        if (new TextEncoder().encode(raw).length > MAX_STORAGE_BYTES) throw new Error('Локално складиште прелази 256 KiB. Извезите копију и обришите податке.');
        const data = JSON.parse(raw);
        if (data?.version !== 1 || !data.reviews || Array.isArray(data.reviews)
                || Object.keys(data.reviews).length > MAX_REVIEWS
                || data.checkpoint !== null && !validFrame(data.checkpoint)
                || data.lastDisplay !== null && !validFrame(data.lastDisplay)
                || Object.entries(data.reviews).some(([id, value]) => !/^[1-9][0-9]{0,18}$/.test(id)
                    || String(value?.review?.auctionId) !== id || !value.review.revision?.lineage
                    || !Number.isSafeInteger(value.review.revision.sequence) || value.review.revision.sequence < 1
                    || !value.review.revision.runId || typeof value.review.comparisonPolicy !== 'string'
                    || !Number.isFinite(Date.parse(value.review.evaluatedAt)) || !Number.isFinite(Date.parse(value.acknowledgedAt))))
            throw new Error('Неподржани или оштећени локални подаци. Ништа није преписано: извезите копију, па обришите податке.');
        return data;
    }
    write(data) {
        const raw = JSON.stringify(data);
        if (new TextEncoder().encode(raw).length > MAX_STORAGE_BYTES) throw new Error('Достигнута граница 256 KiB. Извезите и намерно обришите потврде; ништа није одбачено.');
        this.local.setItem(STORAGE_KEY, raw); // Quota/blocked errors propagate; never claim success.
    }
    displayed(frame) {
        if (!validFrame(frame)) return;
        const data = this.read();
        const previous = data.lastDisplay;
        if (previous && previous.publication.lineage === frame.publication.lineage
                && (previous.publication.sequence > frame.publication.sequence
                    || previous.publication.sequence === frame.publication.sequence && Date.parse(previous.evaluatedAt) >= Date.parse(frame.evaluatedAt))) return;
        data.lastDisplay = frame;
        this.write(data);
    }
    checkpoint(frame) {
        if (frame !== null && !validFrame(frame)) throw new Error('Нема успешно приказане публикације.');
        const data = this.read(); data.checkpoint = frame; this.write(data);
    }
    acknowledge(review, acknowledgedAt = new Date().toISOString()) {
        const data = this.read(), id = String(review.auctionId);
        if (!data.reviews[id] && Object.keys(data.reviews).length >= MAX_REVIEWS)
            throw new Error('Достигнуто је 200 потврда. Извезите копију и намерно обришите потврде; ништа није одбачено.');
        data.reviews[id] = {review: structuredClone(review), acknowledgedAt};
        this.write(data);
    }
    clearReview(id) { const data = this.read(); delete data.reviews[String(id)]; this.write(data); }
    clearReviews() { const data = this.read(); data.reviews = {}; this.write(data); }
}

export function civilParts(instant) {
    if (!instant || !Number.isFinite(Date.parse(instant))) return {local: '', offset: ''};
    const date = new Date(instant);
    const parts = Object.fromEntries(new Intl.DateTimeFormat('sv-SE', {timeZone: 'Europe/Belgrade',
        year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'})
        .formatToParts(date).map(p => [p.type, p.value]));
    const local = `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}`;
    const hours = Math.round((Date.parse(local + 'Z') - date.getTime()) / 3600000);
    return {local, offset: `${hours < 0 ? '-' : '+'}${String(Math.abs(hours)).padStart(2, '0')}:00`};
}
