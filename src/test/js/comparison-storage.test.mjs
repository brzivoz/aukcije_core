import test from 'node:test';
import assert from 'node:assert/strict';
import {ComparisonStorage, STORAGE_KEY, VISIT_KEY, personalParameters, civilParts} from '../../main/resources/static/comparison-storage.mjs';
class Memory {
    values = new Map();
    getItem(k) { return this.values.get(k) ?? null; }
    setItem(k, v) { this.values.set(k, String(v)); }
    removeItem(k) { this.values.delete(k); }
}
const frame = (sequence, seconds = sequence, lineage = 'lineage') => ({publication: {lineage, sequence, runId: `run-${sequence}`},
    publishedAt: `2026-08-24T10:00:${String(sequence).padStart(2, '0')}Z`, evaluatedAt: `2026-08-24T10:01:${String(seconds).padStart(2, '0')}Z`});
const review = (id, sequence = 1) => ({auctionId: id, revision: frame(sequence).publication,
    evaluatedAt: frame(sequence).evaluatedAt, comparisonPolicy: 'source-review-v1'});

test('first use is not now; successful displays persist without unload; reload freezes this visit', () => {
    const local = new Memory(), session = new Memory();
    const first = new ComparisonStorage(local, session);
    assert.equal(first.previous, null);
    assert.throws(() => personalParameters(first.previous));
    first.displayed(frame(1)); first.displayed(frame(2));
    assert.equal(new ComparisonStorage(local, session).previous, null);
    const nextSession = new Memory(), next = new ComparisonStorage(local, nextSession);
    assert.deepEqual(next.previous, frame(2));
    next.displayed(frame(3));
    assert.deepEqual(new ComparisonStorage(local, nextSession).previous, frame(2));
    assert.deepEqual(new ComparisonStorage(local, new Memory()).previous, frame(3));
});
test('tabs freeze their own preceding display and out-of-order older views never regress last display', () => {
    const local = new Memory(); const a = new ComparisonStorage(local, new Memory());
    a.displayed(frame(1));
    const b = new ComparisonStorage(local, new Memory());
    a.displayed(frame(3)); b.displayed(frame(2));
    assert.deepEqual(b.previous, frame(1)); assert.equal(a.previous, null);
    assert.deepEqual(a.read().lastDisplay, frame(3));
    b.displayed(frame(3, 4)); a.displayed(frame(3, 3));
    assert.deepEqual(a.read().lastDisplay, frame(3, 4));
});
test('explicit checkpoint stores the captured older displayed publication, never latest or mapDataVersion', () => {
    const store = new ComparisonStorage(new Memory(), new Memory());
    store.displayed(frame(1)); const displayed = frame(1);
    store.displayed(frame(2)); store.checkpoint(displayed);
    assert.deepEqual(store.read().checkpoint, frame(1));
    store.displayed(frame(3)); assert.deepEqual(store.read().checkpoint, frame(1));
    assert.deepEqual(personalParameters(store.read().checkpoint), {since: 'publication', sinceAt: frame(1).evaluatedAt, publication: 'lineage:1:run-1'});
    store.displayed(frame(1, 1, 'restored'));
    assert.deepEqual(store.read().checkpoint, frame(1)); // Foreign lineage is retained for explicit server validation.
    store.checkpoint(null); assert.equal(store.read().checkpoint, null);
});
test('review is explicit, immutable displayed revision/evaluation plus separate acknowledgement time', () => {
    const store = new ComparisonStorage(new Memory(), new Memory()); store.displayed(frame(2));
    assert.deepEqual(store.read().reviews, {});
    const shown = review(1, 1); store.acknowledge(shown, '2099-01-01T00:00:00Z');
    shown.revision.sequence = 999;
    store.acknowledge(review(2, 2));
    assert.equal(store.read().reviews['1'].review.revision.sequence, 1);
    assert.equal(store.read().reviews['1'].review.evaluatedAt, frame(1).evaluatedAt);
    assert.equal(store.read().reviews['2'].review.revision.sequence, 2);
    store.checkpoint(frame(2)); store.clearReview(1);
    assert.equal(store.read().reviews['1'], undefined); assert.ok(store.read().reviews['2']);
    store.clearReviews(); assert.deepEqual(store.read().checkpoint, frame(2));
});
test('capacity and storage failure never discard acknowledgements or claim success', () => {
    const local = new Memory(), store = new ComparisonStorage(local, new Memory());
    for (let i = 1; i <= 200; i++) store.acknowledge(review(i));
    const previous = local.getItem(STORAGE_KEY);
    assert.throws(() => store.acknowledge(review(201)), /200/);
    assert.equal(local.getItem(STORAGE_KEY), previous);
    store.acknowledge(review(1, 2)); assert.equal(Object.keys(store.read().reviews).length, 200);
    local.setItem = () => { throw new Error('QuotaExceededError'); };
    assert.throws(() => store.checkpoint(frame(2)), /Quota/);
    assert.equal(store.read().checkpoint, null);
    const blocked = {getItem() { throw new Error('blocked'); }};
    assert.throws(() => new ComparisonStorage(blocked, new Memory()), /blocked/);
    assert.throws(() => new ComparisonStorage(new Memory(), blocked), /blocked/);
});
test('corrupt, future and oversized storage requires explicit recovery; cleared storage is not zero changes', () => {
    const local = new Memory(), session = new Memory();
    for (const invalid of ['invalid', '{"version":2,"reviews":{}}', 'x'.repeat(256 * 1024 + 1)]) {
        local.setItem(STORAGE_KEY, invalid);
        assert.throws(() => new ComparisonStorage(local, session));
        assert.equal(local.getItem(STORAGE_KEY), invalid);
    }
    local.removeItem(STORAGE_KEY); session.removeItem(VISIT_KEY);
    const store = new ComparisonStorage(local, session);
    assert.equal(store.previous, null); assert.equal(store.read().checkpoint, null);
});
test('Belgrade chosen-time restoration distinguishes DST overlap offsets', () => {
    assert.deepEqual(civilParts('2026-10-25T00:30:00Z'), {local: '2026-10-25T02:30:00', offset: '+02:00'});
    assert.deepEqual(civilParts('2026-10-25T01:30:00Z'), {local: '2026-10-25T02:30:00', offset: '+01:00'});
});
