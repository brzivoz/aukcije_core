/** Progressive enhancement of native details/checkboxes, not another filter model. */
export function createMunicipalitySelect(root) {
    const summary = root.querySelector('summary');
    const text = root.querySelector('#municipality-summary');
    const options = root.querySelector('#municipality-options');
    const search = root.querySelector('#municipality-search');
    const empty = root.querySelector('#municipality-empty');
    const checkboxes = () => [...options.querySelectorAll('input[name="municipality"]')];
    const values = () => checkboxes().filter(input => input.checked).map(input => input.value);

    function updateSummary() {
        const selected = values();
        text.textContent = selected.length ? selected.join(', ') : 'Све општине';
        summary.title = text.textContent;
    }
    function filterOptions() {
        const query = normalize(search.value.trim());
        for (const label of options.children) {
            label.hidden = !normalize(label.textContent).includes(query);
        }
        empty.hidden = [...options.children].some(label => !label.hidden);
    }
    function setValues(selected) {
        const wanted = new Set(selected);
        checkboxes().forEach(input => { input.checked = wanted.has(input.value); });
        updateSummary();
    }
    function refreshOptions(names) {
        const draft = values();
        const labels = new Map(checkboxes().map(input => [input.value, input.closest('label')]));
        // Keep selected drafts even if source data/options change while the form is being edited.
        const next = [...new Set([...names, ...draft])];
        const previous = [...labels.keys()];
        if (next.length === previous.length && next.every((name, index) => name === previous[index])) return;
        const focused = options.contains(document.activeElement) ? document.activeElement : null;
        options.replaceChildren(...next.map(name => {
            if (labels.has(name)) return labels.get(name);
            const label = document.createElement('label');
            label.className = 'municipality-option';
            const input = document.createElement('input');
            input.type = 'checkbox'; input.name = 'municipality'; input.value = name;
            const caption = document.createElement('span'); caption.textContent = name;
            label.append(input, caption);
            return label;
        }));
        filterOptions();
        updateSummary();
        if (focused?.isConnected) focused.focus({preventScroll: true});
    }

    root.querySelector('.municipality-tools').hidden = false;
    options.addEventListener('change', updateSummary);
    search.addEventListener('input', filterOptions);
    root.querySelector('#municipality-clear').addEventListener('click', () => {
        setValues([]);
        search.value = '';
        filterOptions();
    });
    root.addEventListener('keydown', event => {
        if (event.key === 'Escape') {
            event.preventDefault();
            root.open = false;
            summary.focus();
        }
    });
    document.addEventListener('click', event => {
        if (!root.contains(event.target)) root.open = false;
    });
    updateSummary();
    return {setValues, refreshOptions};
}

// Local option search accepts Cyrillic or Serbian Latin; it never changes submitted names.
const CYRILLIC = Object.fromEntries([...'абвгдђежзијклљмнњопрстћуфхцчџш'].map((letter, index) =>
    [letter, ['a','b','v','g','d','dj','e','z','z','i','j','k','l','lj','m','n','nj','o','p','r','s','t','c','u','f','h','c','c','dz','s'][index]]));
function normalize(value) {
    return value.toLocaleLowerCase('sr').replace(/ђ|đ/g, 'dj')
            .replace(/[а-яђјљњћџ]/g, letter => CYRILLIC[letter] || letter)
            .normalize('NFD').replace(/\p{M}/gu, '');
}
