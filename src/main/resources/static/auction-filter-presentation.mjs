import {civilParts} from './comparison-storage.mjs';

/** Read-only projection of the shared view's applied query; drafts remain native controls. */
export function createFilterPresentation({form, fields, appliedQuery, removeCriterion, reset}) {
    const chips = document.getElementById('applied-filter-chips');
    const advanced = document.getElementById('advanced-filters');
    const labels = {
        municipality: 'Општина', placeName: 'Место', category: 'Категорија', status: 'Изворни статус',
        minPrice: 'Мин. РСД', maxPrice: 'Макс. РСД', firstSale: 'Прва продаја', search: 'Претрага',
        precision: 'Прецизност', parcelSize: 'Површина парцеле',
        from: 'Завршетак од', to: 'Завршетак до', timeScope: 'Временски опсег',
        since: 'Промене од', changeKind: 'Врста промена', liveBidding: 'Лицитирање',
        sinceLocal: 'Датум/време (Београд)', sinceOffset: 'UTC помак', sinceAt: 'Тачка поређења', publication: 'Публикација'
    };
    let rendered = null;
    const chipFields = fields.filter(name => !['sinceAt', 'publication', 'sinceLocal', 'sinceOffset'].includes(name));
    function values(query, name) {
        if (['sinceLocal', 'sinceOffset'].includes(name)) {
            if (query.get('since') !== 'date') return [];
            if (query instanceof URLSearchParams) {
                const parts = civilParts(query.get('sinceAt'));
                return [name === 'sinceLocal' ? parts.local : parts.offset].filter(Boolean);
            }
        }
        if (name === 'sinceAt' && query.get('since') === 'date') return [];
        if (name === 'publication' && query.get('since') !== 'publication') return [];
        const raw = query.getAll(name).map(value => name === 'sinceLocal' && value.length === 16 ? value + ':00' : value.trim()).filter(Boolean);
        if (name === 'timeScope' && !raw.length) return ['not-ended'];
        return [...new Set(raw.map(value => {
            // RSD allows 17 integer digits: never round a chip/draft through IEEE-754.
            if (!['minPrice', 'maxPrice'].includes(name) || !/^\d+(\.\d+)?$/.test(value)) return value;
            const [integer, fraction = ''] = value.split('.');
            const decimals = fraction.replace(/0+$/, '');
            return integer.replace(/^0+(?=\d)/, '') + (decimals ? `.${decimals}` : '');
        }))].sort();
    }
    function caption(name, value) {
        const control = form.elements.namedItem(name);
        const label = control?.options ? [...control.options].find(option => option.value === value)?.textContent : value;
        return name === 'timeScope' ? label || value : `${labels[name]}: ${label || value}`;
    }
    function update() {
        const query = appliedQuery();
        const draft = new FormData(form);
        const dirty = fields.some(name => JSON.stringify(values(draft, name)) !== JSON.stringify(values(query, name)));
        document.getElementById('filter-dirty-indicator').hidden = !dirty;
        document.getElementById('filter-draft-state').textContent = dirty
            ? 'Непримењене измене. Карта и табела користе приказане примењене филтере.' : '';
        const active = chipFields.filter(name => values(query, name).length).length;
        document.getElementById('filter-active-count').textContent = `· ${active}`;
        const advancedCount = fields.filter(name => advanced.querySelector(`[name="${name}"]`) && values(query, name).length).length;
        document.getElementById('advanced-filter-count').textContent = advancedCount ? `· ${advancedCount} примењено` : '';
        // Polls must not replace a focused chip or re-announce an unchanged summary.
        const key = JSON.stringify(chipFields.map(name => [name, values(query, name), values(query, name).map(value => caption(name, value))]));
        if (key === rendered) return;
        rendered = key;
        const focused = chips.contains(document.activeElement);
        chips.replaceChildren();
        for (const name of ['timeScope', ...chipFields.filter(name => name !== 'timeScope')]) {
            for (const value of values(query, name)) {
                const text = caption(name, value);
                const chip = document.createElement('button');
                chip.type = 'button';
                chip.className = 'filter-chip';
                chip.dataset.field = name;
                chip.dataset.value = value;
                const isDefault = name === 'timeScope' && value === 'not-ended';
                chip.textContent = text + (isDefault ? '' : ' ×');
                chip.setAttribute('aria-label', isDefault ? `Измени временски опсег: ${text}`
                    : name === 'timeScope' ? `Врати временски опсег на Нису завршене: ${text}` : `Уклони филтер: ${text}`);
                chip.addEventListener('click', () => {
                    if (isDefault) window.dispatchEvent(new CustomEvent('eaukcija:reveal-filter', {detail: {field: name}}));
                    else removeCriterion(name, value);
                });
                chips.append(chip);
            }
        }
        if (focused) chips.querySelector('button')?.focus({preventScroll: true});
    }
    form.addEventListener('input', update);
    form.addEventListener('change', update);
    document.getElementById('municipality-clear').addEventListener('click', update);
    document.getElementById('applied-filter-reset').addEventListener('click', reset);
    document.getElementById('workspace-applied').hidden = false;
    update();
    return {update, label: name => labels[name] || name};
}
