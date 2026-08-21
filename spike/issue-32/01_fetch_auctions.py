#!/usr/bin/env python3
"""Stage 1 - pull the root-7 auction population (listing + per-auction details).

The whole result set comes back in one request at ItemCount=3000, so there is
no paging loop to get right. Details are fetched one auction at a time and
cached individually, so an interrupted run resumes for free.

    python3 01_fetch_auctions.py                  # live pull
    python3 01_fetch_auctions.py --source fixture # committed 86-record sample

--source fixture exists because a sandboxed/offline environment cannot reach
eaukcija.sud.rs. It produces the same corpus.json shape so stages 2-6 run
unchanged, but it is a strictly smaller and partly stale population - see the
decision record before quoting any number produced from it.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

from common import FIXTURE, OUT, out, read_json, write_json

BASE = "https://eaukcija.sud.rs/WebApi.Proxy/api/EAukcija"
LIST_URL = f"{BASE}/GetAuctionsByCategoryId"
DETAIL_URL = f"{BASE}/GetImmovablePropertyDetails"
ROOT_CATEGORY = 7
CACHE = os.path.join(OUT, "details")


def post(url, payload, timeout=90):
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": "aukcije-core-spike-32/1.0",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def unwrap_api_response(response, operation):
    """Validate the eAukcija envelope and return its Data payload."""
    if not isinstance(response, dict):
        raise RuntimeError(f"{operation}: expected an object response")
    if "ResultCode" not in response:
        # Kept for a legacy cache written before the envelope was handled.
        return response
    if str(response.get("ResultCode")) != "0":
        raise RuntimeError(
            f"{operation}: API result {response.get('ResultCode')}: "
            f"{response.get('ResultMessage')}"
        )
    if "Data" not in response or response["Data"] is None:
        raise RuntimeError(f"{operation}: successful response has no Data")
    return response["Data"]


def dedupe_by_id(rows):
    """Return one row per auction id, preserving the API order."""
    unique, seen = [], set()
    for row in rows:
        aid = row.get("Id") if isinstance(row, dict) else None
        if aid is None:
            raise RuntimeError("listing contains a row without Id")
        if aid in seen:
            continue
        seen.add(aid)
        unique.append(row)
    return unique, len(rows) - len(unique)


def strip_large_payloads(row):
    """Remove image blobs that are irrelevant to the measurement."""
    if not isinstance(row, dict):
        raise RuntimeError("auction/detail payload is not an object")
    row.pop("Thumbnail", None)
    row.pop("Images", None)
    slip = row.get("GuaranteeSlip")
    if isinstance(slip, dict):
        slip.pop("Base64", None)
    return row


def fetch_live(item_count, delay):
    print(f"listing: POST {LIST_URL} ItemCount={item_count}", file=sys.stderr)
    t0 = time.time()
    listing = unwrap_api_response(post(LIST_URL, {
        "CategoryId": ROOT_CATEGORY,
        "ItemCount": item_count,
        "PageCount": 1,
    }), "GetAuctionsByCategoryId")
    if not isinstance(listing, dict) or not isinstance(listing.get("Auctions"), list):
        raise RuntimeError("listing Data does not contain Auctions[]")
    rows = listing["Auctions"]
    total_count = int(listing.get("TotalCount", len(rows)))
    if total_count > item_count:
        raise RuntimeError(
            f"ItemCount={item_count} truncated TotalCount={total_count}; increase it"
        )
    if len(rows) != total_count:
        raise RuntimeError(
            f"listing returned {len(rows)} rows for TotalCount={total_count}"
        )
    rows, duplicate_count = dedupe_by_id(rows)
    print(
        f"listing: {len(rows)} unique records ({duplicate_count} duplicates) "
        f"in {time.time() - t0:.1f}s",
        file=sys.stderr,
    )

    os.makedirs(CACHE, exist_ok=True)
    corpus, failures = [], []
    for i, row in enumerate(rows, 1):
        aid = row.get("Id")
        cached = os.path.join(CACHE, f"{aid}.json")
        if os.path.exists(cached):
            detail = unwrap_api_response(read_json(cached), f"cached detail {aid}")
            strip_large_payloads(detail)
        else:
            detail, last_error = None, None
            for attempt in range(1, 4):
                try:
                    detail = unwrap_api_response(
                        post(DETAIL_URL, {"AuctionId": aid}),
                        f"GetImmovablePropertyDetails({aid})",
                    )
                    break
                except (urllib.error.URLError, TimeoutError, OSError,
                        json.JSONDecodeError, RuntimeError) as exc:
                    last_error = exc
                    if attempt < 3:
                        time.sleep(attempt)
            if detail is None:
                failures.append({"id": aid, "error": str(last_error)})
                print(f"  detail {aid} failed: {last_error}", file=sys.stderr)
            else:
                strip_large_payloads(detail)
                write_json(cached, detail)
                time.sleep(delay)
        strip_large_payloads(row)
        row["_detalji"] = detail
        corpus.append(row)
        if i % 50 == 0:
            print(f"  details {i}/{len(rows)}", file=sys.stderr)
    if failures:
        write_json(out("detail_failures.json"), failures)
        raise RuntimeError(
            f"{len(failures)} detail requests failed; rerun to resume from cache"
        )
    return corpus, {
        "total_count": total_count,
        "duplicate_records_removed": duplicate_count,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", choices=["live", "fixture"], default="live")
    ap.add_argument("--item-count", type=int, default=3000)
    ap.add_argument("--delay", type=float, default=0.2,
                    help="delay between successful detail requests")
    args = ap.parse_args()

    if args.source == "fixture":
        fixture_rows = read_json(FIXTURE)
        corpus, duplicate_count = dedupe_by_id(fixture_rows)
        provenance = {
            "source": "fixture",
            "path": os.path.relpath(FIXTURE),
            "input_records": len(fixture_rows),
            "duplicate_records_removed": duplicate_count,
        }
        print(
            f"fixture: {len(corpus)} unique records "
            f"({duplicate_count} duplicates removed)",
            file=sys.stderr,
        )
    else:
        try:
            corpus, live_meta = fetch_live(args.item_count, args.delay)
        except (urllib.error.URLError, TimeoutError, OSError,
                json.JSONDecodeError, RuntimeError) as exc:
            print(f"\nFATAL: live pull failed: {exc}", file=sys.stderr)
            print("If this host cannot reach eaukcija.sud.rs, re-run with "
                  "--source fixture and label the results accordingly.",
                  file=sys.stderr)
            return 2
        provenance = {"source": "live", "url": LIST_URL,
                      "category_id": ROOT_CATEGORY,
                      "item_count": args.item_count, **live_meta}

    provenance["fetched_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    provenance["records"] = len(corpus)
    provenance["records_with_details"] = sum(1 for a in corpus if a.get("_detalji"))

    write_json(out("corpus.json"), corpus)
    write_json(out("corpus_provenance.json"), provenance)
    print(json.dumps(provenance, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
