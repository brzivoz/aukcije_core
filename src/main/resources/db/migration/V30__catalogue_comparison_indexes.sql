-- #56: current-membership intersections use per-identity activity probes, not latest deltas.
CREATE INDEX idx_source_observations_meaningful ON sync_run_auction_observations(auction_id, publication_id)
    WHERE comparison_kind IN ('SUBSTANTIVE', 'LIVE_BIDDING_ONLY');
CREATE INDEX idx_source_observations_new ON sync_run_auction_observations(auction_id, publication_id)
    WHERE content_delta = 'NEW';
CREATE INDEX idx_source_observations_comparison_gaps ON sync_run_auction_observations(publication_id)
    WHERE comparison_kind IN ('BASELINE', 'UNSUPPORTED') AND content_delta <> 'NEW';
