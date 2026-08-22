-- Issue #15: B-tree coverage for the existing UI facet filters and price sort.
-- Free-text contains search remains unindexed until its owning search issue
-- chooses a PostgreSQL text-search/trigram contract.
CREATE INDEX idx_auctions_municipality ON auctions (municipality);
CREATE INDEX idx_auctions_place_name ON auctions (place_name);
CREATE INDEX idx_auctions_category_name ON auctions (category_name);
CREATE INDEX idx_auctions_status ON auctions (status);
CREATE INDEX idx_auctions_starting_price ON auctions (starting_price);
