-- Municipality multi-selection uses case-insensitive name equality; source text is unchanged.
CREATE INDEX auctions_municipality_lower_idx ON auctions (lower(municipality));
