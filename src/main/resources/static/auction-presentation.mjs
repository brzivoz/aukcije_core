/** Display-only labels: raw workflow values stay in filters/URLs and never imply geometry verification. */
export function statusLabel(value) {
    const labels = {InPrediction: 'У најави', Published: 'Објављено', Verification: 'У провери',
        Verified: 'Проверено на извору', InProgress: 'У току на извору',
        Completed: 'Окончано на извору', Closed: 'Затворено на извору'};
    return !value ? 'Статус није познат' : Object.hasOwn(labels, value) ? labels[value] : `Непознат изворни статус: ${value}`;
}
export function category(properties) { return properties.category || properties.propertyKind || 'Категорија није наведена'; }
export function locality(properties) {
    return `Место: ${properties.placeName || 'није наведено'} · Општина: ${properties.municipality || 'није наведена'}`;
}
export function auctionNumber(properties) { return `Број аукције: ${properties.auctionNumber || 'није наведен'}`; }
export function setText(element, value) {
    // Keep selected text and connected controls on unchanged refreshes.
    if (element.textContent !== value) element.textContent = value;
}
