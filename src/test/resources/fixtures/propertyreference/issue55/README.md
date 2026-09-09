# Known #55 full-context regression

`181104-current.json` is the complete minimized enrichment input retained locally
for auction 181104 on 2026-09-09. It includes the **entire** Description and
ShortDescription, the structured place fields, and the retained source-snapshot
hash. No ownership names, source credentials or unrelated detail payloads occur
in this input. Parser tests check exact UTF-16 evidence spans; the PostGIS
workflow fixture reuses both descriptions verbatim.

This is a coding-agent-reviewed regression, not independent human annotation or
fresh held-out quality evidence. The auction already appears in the frozen v1
held-out snippet corpus with a different retained snapshot. Do not count it as
an independent v2 evaluation example, and do not modify that frozen corpus.

The full-workflow tests serve synthetic geometry from a loopback RGZ fixture and
synthetic imported registry points. They prove identity, fallback, provenance,
selection, replay and map behavior, not the real parcel boundary or address
coordinate availability in Serbia.
