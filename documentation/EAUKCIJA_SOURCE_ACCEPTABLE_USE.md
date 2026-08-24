# eAukcija source acceptable-use note

Checked: 2026-08-24

This note records the source-safety decision for the small, local eAukcija
ingestion client. It is not legal advice and does not claim that automated use
has been authorized.

## Public material reviewed

No standalone public terms for automation, acceptable-use policy, or published
API contract was located on the public [eAukcija portal](https://eaukcija.sud.rs/)
on 2026-08-24. The official
[external-user manual](https://eaukcija.sud.rs/assets/pdf/UputstvoZaEksterneKorisnikeV5.1.pdf)
mentions application terms only as part of the user-registration flow. It does
not document unattended access, rate limits, or a public data API.

The portal's [`robots.txt`](https://eaukcija.sud.rs/robots.txt) returned HTTP
`404 Not Found` on the same date. A missing robots file is not permission to
automate access.

The application reads the `WebApi.Proxy/api/EAukcija/*` JSON routes used by the
portal's own single-page application. Those routes are an undocumented SPA
backend, not a published or supported API. The client must therefore assume the
contract can change without notice and fail visibly instead of scraping around
a changed contract. The dated
[SPA bundle](https://eaukcija.sud.rs/dist/bundle.js?ver=1.2.0) routes
`ImmovableProperties` and `CommonProperties` through the distinct
`GetImmovablePropertyDetails` and `GetCommonPropertyDetails` methods; the client
preserves that separation for configured roots `7` and `8`.

## Chosen safeguards

The version-controlled defaults are deliberately conservative:

- at most `2` requests per second across the client;
- maximum request concurrency `1`;
- bounded timeouts, response sizes, retries, and exponential backoff;
- full observance of a usable upstream `Retry-After` value; and
- the contact-bearing User-Agent
  `aukcije-core/0.0.1 (+https://github.com/brzivoz/aukcije_core/issues)`.

Operators may tune only within the documented safe bounds. A `Retry-After`
longer than the configured retry budget stops the run; the client must never
shorten the source's requested delay merely to finish a run.

No official email or other contact request was sent for this review because the
task did not authorize external communication. The repository issue URL in the
User-Agent provides an inbound contact path, but it is not a substitute for
permission. If scheduled or materially higher-volume use is proposed, the
operator should obtain an explicit source-owner decision before changing these
safeguards.

The absence of a published policy, an API contract, or `robots.txt` is not
permission. Stop scheduled ingestion if the source objects, publishes
incompatible terms, or indicates that the traffic is harmful.
