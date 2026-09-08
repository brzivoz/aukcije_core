-- Shared Serbian Cyrillic/Latin substring search. Original source/display text is untouched.
-- Digraphs and diacritics share one ASCII search spelling, not a property taxonomy.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- One-to-one transliteration after expanding Serbian digraphs.
CREATE FUNCTION auction_search_normalize(value text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT translate(
        replace(replace(replace(replace(replace(lower(coalesce(value, '')),
            'љ', 'lj'), 'њ', 'nj'), 'џ', 'dz'), 'ђ', 'dj'), 'đ', 'dj'),
        'абвгдежзијклмнопрстћуфхцчшčćšž',
        'abvgdezzijklmnoprstcufhccsccsz'
    )
$$;

CREATE FUNCTION auction_search_text(number text, short_text text, detail_text text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT public.auction_search_normalize(coalesce(number, '') || ' ' || coalesce(short_text, '') || ' ' || coalesce(detail_text, ''))
$$;

CREATE INDEX auctions_source_status_lower_idx ON auctions (lower(status));

CREATE INDEX auctions_shared_search_trgm_idx ON auctions
    USING gin (auction_search_text(auction_number, short_description, description) gin_trgm_ops);
