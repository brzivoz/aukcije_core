#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
# shellcheck source=versions.env
source "$SCRIPT_DIR/versions.env"

for command_name in python3; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    printf 'Missing required command: %s\n' "$command_name" >&2
    exit 2
  fi
done

DATA_ROOT=${BASEMAP_DATA_ROOT:-"$REPO_ROOT/data/basemap"}
JAVA_HEAP=${BASEMAP_JAVA_HEAP:-3g}
THREADS=${BASEMAP_THREADS:-4}
case "$DATA_ROOT" in
  /|"$REPO_ROOT"|"${HOME:-__unset__}")
    printf 'Refusing unsafe BASEMAP_DATA_ROOT: %s\n' "$DATA_ROOT" >&2
    exit 2
    ;;
esac

CONFIG_HASH=$(python3 "$SCRIPT_DIR/scripts/config_hash.py" \
  --root "$REPO_ROOT" \
  --value "BASEMAP_JAVA_HEAP=$JAVA_HEAP" \
  --value "BASEMAP_THREADS=$THREADS" \
  "$SCRIPT_DIR/build.sh" \
  "$SCRIPT_DIR/versions.env" \
  "$SCRIPT_DIR/assets.lock" \
  "$SCRIPT_DIR/profile.yml" \
  "$SCRIPT_DIR/style.json" \
  "$SCRIPT_DIR/THIRD_PARTY_NOTICES.md" \
  "$SCRIPT_DIR/scripts/acquire_lock.py" \
  "$SCRIPT_DIR/scripts/config_hash.py" \
  "$SCRIPT_DIR/scripts/verify_source.py" \
  "$SCRIPT_DIR/scripts/validate_bundle.py" \
  "$SCRIPT_DIR/scripts/write_manifest.py")
BUILD_ID="serbia-${SOURCE_DATE}-${CONFIG_HASH:0:12}"
CACHE_ROOT="$DATA_ROOT/cache"
SOURCE_ROOT="$CACHE_ROOT/source"
TOOL_ROOT="$CACHE_ROOT/tools/pmtiles-$PMTILES_VERSION"
ASSET_ROOT="$CACHE_ROOT/assets"
WORK_ROOT="$DATA_ROOT/work/$BUILD_ID"
BUNDLE_ROOT="$WORK_ROOT/bundle"
TARGET_ROOT="$DATA_ROOT/builds/$BUILD_ID"
LOCK_ROOT="$DATA_ROOT/.build-$BUILD_ID.lock"
LOCK_HOST=$(uname -n)
EXTRACT_ROOT=

BUILD_USER="$(id -u):$(id -g)"
PLANETILER_COMMAND=(
  docker run --rm
  --user "$BUILD_USER"
  --env "JAVA_TOOL_OPTIONS=-Xmx$JAVA_HEAP"
  --volume "$SOURCE_ROOT:/inputs:ro"
  --volume "$WORK_ROOT:/work"
  --volume "$SCRIPT_DIR:/config:ro"
  "$PLANETILER_IMAGE" generate-custom
  --schema=/config/profile.yml
  "--osm-path=/inputs/$SOURCE_FILENAME"
  --output=/work/bundle/serbia.pmtiles
  --tmpdir=/work/tmp
  "--bounds=$BASEMAP_BOUNDS"
  "--minzoom=$BASEMAP_MIN_ZOOM"
  "--maxzoom=$BASEMAP_MAX_ZOOM"
  "--render-maxzoom=$BASEMAP_MAX_ZOOM"
  "--threads=$THREADS"
  --storage=mmap
  --nodemap-type=sortedtable
  --nodemap-storage=mmap
  --mmap-temp=false
  --compress-temp=true
  --tile-format=mvt
  --tile-compression=gzip
  --use-wikidata=false
  --force
)

# Derive the publishable provenance from the exact execution argv, replacing
# only values supplied by the host. This keeps the manifest deterministic and
# prevents local paths and account identifiers from being served with it.
PLANETILER_MANIFEST_COMMAND=("${PLANETILER_COMMAND[@]}")
for argument_index in "${!PLANETILER_MANIFEST_COMMAND[@]}"; do
  case "${PLANETILER_MANIFEST_COMMAND[$argument_index]}" in
    "$BUILD_USER") PLANETILER_MANIFEST_COMMAND[$argument_index]='<uid>:<gid>' ;;
    "$SOURCE_ROOT:/inputs:ro")
      PLANETILER_MANIFEST_COMMAND[$argument_index]='<source-cache>:/inputs:ro'
      ;;
    "$WORK_ROOT:/work") PLANETILER_MANIFEST_COMMAND[$argument_index]='<work>:/work' ;;
    "$SCRIPT_DIR:/config:ro")
      PLANETILER_MANIFEST_COMMAND[$argument_index]='<basemap-config>:/config:ro'
      ;;
  esac
done
PLANETILER_COMMAND_TEXT=
for command_argument in "${PLANETILER_MANIFEST_COMMAND[@]}"; do
  case "$command_argument" in
    '<uid>:<gid>'|'<source-cache>:/inputs:ro'|'<work>:/work'|'<basemap-config>:/config:ro')
      quoted_argument=$command_argument
      ;;
    *) printf -v quoted_argument '%q' "$command_argument" ;;
  esac
  PLANETILER_COMMAND_TEXT+="${PLANETILER_COMMAND_TEXT:+ }$quoted_argument"
done

if [[ "${BASEMAP_PRINT_COMMAND:-0}" == 1 ]]; then
  printf '%s\n' "$PLANETILER_COMMAND_TEXT"
  exit 0
fi

for command_name in docker curl tar unzip; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    printf 'Missing required command: %s\n' "$command_name" >&2
    exit 2
  fi
done

cleanup_extract() {
  if [[ -n "$EXTRACT_ROOT" && -d "$EXTRACT_ROOT" ]]; then
    case "$EXTRACT_ROOT" in
      "$TOOL_ROOT"/.extract-*) find "$EXTRACT_ROOT" -depth -delete ;;
      *) printf 'Refusing to clean unexpected extraction path: %s\n' "$EXTRACT_ROOT" >&2 ;;
    esac
  fi
}
cleanup_lock() {
  python3 "$SCRIPT_DIR/scripts/acquire_lock.py" release \
    --data-root "$DATA_ROOT" \
    --lock-root "$LOCK_ROOT" \
    --pid "$$" \
    --host "$LOCK_HOST" >/dev/null 2>&1 || true
  cleanup_extract
}
trap cleanup_lock EXIT

mkdir -p "$SOURCE_ROOT" "$TOOL_ROOT" "$ASSET_ROOT" "$DATA_ROOT/builds" "$DATA_ROOT/work"
python3 "$SCRIPT_DIR/scripts/acquire_lock.py" acquire \
  --data-root "$DATA_ROOT" \
  --lock-root "$LOCK_ROOT" \
  --pid "$$" \
  --host "$LOCK_HOST"

download_once() {
  local url=$1
  local target=$2
  if [[ -f "$target" ]]; then
    return
  fi
  mkdir -p "$(dirname -- "$target")"
  local partial="${target}.part.$$"
  if ! curl --fail --location --retry 4 --retry-all-errors \
      --user-agent 'aukcije_core basemap builder (https://github.com/brzivoz/aukcije_core)' \
      --output "$partial" "$url"; then
    rm -f -- "$partial"
    printf 'Download failed; removed partial file %s. Rerun to retry.\n' "$partial" >&2
    return 2
  fi
  if ! mv "$partial" "$target"; then
    rm -f -- "$partial"
    return 2
  fi
}

hash_file() {
  python3 -c 'import hashlib, pathlib, sys; print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())' "$1"
}

verify_locked_file() {
  local path=$1
  local expected_hash=$2
  local expected_size=$3
  local actual_hash
  local actual_size
  actual_hash=$(hash_file "$path")
  actual_size=$(wc -c < "$path" | tr -d ' ')
  if [[ "$actual_hash" != "$expected_hash" || "$actual_size" != "$expected_size" ]]; then
    printf 'Pinned download mismatch: %s\n' "$path" >&2
    printf 'Expected sha256/size: %s / %s\n' "$expected_hash" "$expected_size" >&2
    printf 'Actual sha256/size:   %s / %s\n' "$actual_hash" "$actual_size" >&2
    rm -f -- "$path"
    printf 'Removed the invalid cache file; rerun to download it again.\n' >&2
    exit 2
  fi
}

select_pmtiles_archive() {
  local system
  local machine
  system=$(uname -s)
  machine=$(uname -m)
  case "$system-$machine" in
    Darwin-arm64)
      PMTILES_ARCHIVE_URL=$PMTILES_DARWIN_ARM64_URL
      PMTILES_ARCHIVE_SHA256=$PMTILES_DARWIN_ARM64_SHA256
      PMTILES_ARCHIVE_SIZE=$PMTILES_DARWIN_ARM64_SIZE
      PMTILES_ARCHIVE_KIND=zip
      ;;
    Darwin-x86_64)
      PMTILES_ARCHIVE_URL=$PMTILES_DARWIN_X86_64_URL
      PMTILES_ARCHIVE_SHA256=$PMTILES_DARWIN_X86_64_SHA256
      PMTILES_ARCHIVE_SIZE=$PMTILES_DARWIN_X86_64_SIZE
      PMTILES_ARCHIVE_KIND=zip
      ;;
    Linux-arm64|Linux-aarch64)
      PMTILES_ARCHIVE_URL=$PMTILES_LINUX_ARM64_URL
      PMTILES_ARCHIVE_SHA256=$PMTILES_LINUX_ARM64_SHA256
      PMTILES_ARCHIVE_SIZE=$PMTILES_LINUX_ARM64_SIZE
      PMTILES_ARCHIVE_KIND=tgz
      ;;
    Linux-x86_64|Linux-amd64)
      PMTILES_ARCHIVE_URL=$PMTILES_LINUX_X86_64_URL
      PMTILES_ARCHIVE_SHA256=$PMTILES_LINUX_X86_64_SHA256
      PMTILES_ARCHIVE_SIZE=$PMTILES_LINUX_X86_64_SIZE
      PMTILES_ARCHIVE_KIND=tgz
      ;;
    *)
      printf 'Unsupported host for pinned go-pmtiles binary: %s-%s\n' "$system" "$machine" >&2
      exit 2
      ;;
  esac
}

select_pmtiles_archive
PMTILES_BIN="$TOOL_ROOT/$(uname -s)-$(uname -m)/pmtiles"
for stale_extract in "$TOOL_ROOT"/.extract-*; do
  [[ -d "$stale_extract" ]] || continue
  stale_pid=${stale_extract##*.extract-}
  if [[ "$stale_pid" =~ ^[0-9]+$ ]] && ! kill -0 "$stale_pid" 2>/dev/null; then
    find "$stale_extract" -depth -delete
  fi
done
if [[ ! -x "$PMTILES_BIN" ]]; then
  PMTILES_ARCHIVE="$TOOL_ROOT/$(basename -- "$PMTILES_ARCHIVE_URL")"
  download_once "$PMTILES_ARCHIVE_URL" "$PMTILES_ARCHIVE"
  verify_locked_file "$PMTILES_ARCHIVE" "$PMTILES_ARCHIVE_SHA256" "$PMTILES_ARCHIVE_SIZE"
  EXTRACT_ROOT="$TOOL_ROOT/.extract-$$"
  mkdir -p "$EXTRACT_ROOT" "$(dirname -- "$PMTILES_BIN")"
  if [[ "$PMTILES_ARCHIVE_KIND" == zip ]]; then
    unzip -q "$PMTILES_ARCHIVE" -d "$EXTRACT_ROOT"
  else
    tar -xzf "$PMTILES_ARCHIVE" -C "$EXTRACT_ROOT"
  fi
  mv "$EXTRACT_ROOT/pmtiles" "$PMTILES_BIN"
  rmdir "$EXTRACT_ROOT"
  EXTRACT_ROOT=
  chmod 0755 "$PMTILES_BIN"
fi

if [[ -d "$TARGET_ROOT" ]]; then
  python3 "$SCRIPT_DIR/scripts/validate_bundle.py" \
    --bundle "$TARGET_ROOT" \
    --pmtiles "$PMTILES_BIN" \
    --pmtiles-version "$PMTILES_VERSION" \
    --pmtiles-commit "$PMTILES_COMMIT" \
    --bounds "$BASEMAP_BOUNDS" \
    --min-zoom "$BASEMAP_MIN_ZOOM" \
    --max-zoom "$BASEMAP_MAX_ZOOM" \
    --metadata-sha256 "$BASEMAP_METADATA_SHA256" \
    --expected-command "$PLANETILER_COMMAND_TEXT" \
    --require-manifest >/dev/null
  printf '%s\n' "$TARGET_ROOT"
  exit 0
fi

SOURCE_PBF="$SOURCE_ROOT/$SOURCE_FILENAME"
SOURCE_CHECKSUM="$SOURCE_ROOT/$SOURCE_FILENAME.md5"
download_once "$SOURCE_URL" "$SOURCE_PBF"
download_once "$SOURCE_CHECKSUM_URL" "$SOURCE_CHECKSUM"
mkdir -p "$WORK_ROOT"
if ! python3 "$SCRIPT_DIR/scripts/verify_source.py" \
    --pbf "$SOURCE_PBF" \
    --checksum "$SOURCE_CHECKSUM" \
    --expected-name "$SOURCE_FILENAME" \
    --expected-md5 "$SOURCE_MD5" \
    --expected-sha256 "$SOURCE_SHA256" \
    --expected-size "$SOURCE_SIZE" > "$WORK_ROOT/source-report.json"; then
  rm -f -- "$SOURCE_PBF" "$SOURCE_CHECKSUM"
  printf 'Removed the invalid source and checksum cache files; rerun to download them again.\n' >&2
  exit 2
fi

mkdir -p "$BUNDLE_ROOT"
while IFS=$'\t' read -r asset_hash asset_size asset_path asset_url; do
  [[ -z "$asset_hash" || "$asset_hash" == \#* ]] && continue
  cache_file="$ASSET_ROOT/$asset_hash"
  download_once "$asset_url" "$cache_file"
  verify_locked_file "$cache_file" "$asset_hash" "$asset_size"
  mkdir -p "$BUNDLE_ROOT/$(dirname -- "$asset_path")"
  cp "$cache_file" "$BUNDLE_ROOT/$asset_path"
done < "$SCRIPT_DIR/assets.lock"
cp "$SCRIPT_DIR/style.json" "$BUNDLE_ROOT/style.json"
cp "$SCRIPT_DIR/THIRD_PARTY_NOTICES.md" "$BUNDLE_ROOT/THIRD_PARTY_NOTICES.md"

docker run --rm \
  --volume "$SCRIPT_DIR:/config:ro" \
  "$PLANETILER_IMAGE" verify /config/profile.yml

"${PLANETILER_COMMAND[@]}"

python3 "$SCRIPT_DIR/scripts/validate_bundle.py" \
  --bundle "$BUNDLE_ROOT" \
  --pmtiles "$PMTILES_BIN" \
  --pmtiles-version "$PMTILES_VERSION" \
  --pmtiles-commit "$PMTILES_COMMIT" \
  --bounds "$BASEMAP_BOUNDS" \
  --min-zoom "$BASEMAP_MIN_ZOOM" \
  --max-zoom "$BASEMAP_MAX_ZOOM" \
  --metadata-sha256 "$BASEMAP_METADATA_SHA256" > "$BUNDLE_ROOT/validation-report.json"

python3 "$SCRIPT_DIR/scripts/write_manifest.py" \
  --bundle "$BUNDLE_ROOT" \
  --source-report "$WORK_ROOT/source-report.json" \
  --validation-report "$BUNDLE_ROOT/validation-report.json" \
  --build-id "$BUILD_ID" \
  --config-sha256 "$CONFIG_HASH" \
  --source-date "$SOURCE_DATE" \
  --source-url "$SOURCE_URL" \
  --source-checksum-url "$SOURCE_CHECKSUM_URL" \
  --planetiler-version "$PLANETILER_VERSION" \
  --planetiler-commit "$PLANETILER_COMMIT" \
  --planetiler-image "$PLANETILER_IMAGE" \
  --pmtiles-version "$PMTILES_VERSION" \
  --pmtiles-commit "$PMTILES_COMMIT" \
  --command "$PLANETILER_COMMAND_TEXT"

python3 "$SCRIPT_DIR/scripts/validate_bundle.py" \
  --bundle "$BUNDLE_ROOT" \
  --pmtiles "$PMTILES_BIN" \
  --pmtiles-version "$PMTILES_VERSION" \
  --pmtiles-commit "$PMTILES_COMMIT" \
  --bounds "$BASEMAP_BOUNDS" \
  --min-zoom "$BASEMAP_MIN_ZOOM" \
  --max-zoom "$BASEMAP_MAX_ZOOM" \
  --metadata-sha256 "$BASEMAP_METADATA_SHA256" \
  --expected-command "$PLANETILER_COMMAND_TEXT" \
  --require-manifest > "$WORK_ROOT/final-validation.json"

mv "$BUNDLE_ROOT" "$TARGET_ROOT"
case "$WORK_ROOT" in
  "$DATA_ROOT"/work/serbia-*) find "$WORK_ROOT" -depth -delete ;;
  *) printf 'Refusing to clean unexpected work path: %s\n' "$WORK_ROOT" >&2; exit 2 ;;
esac
printf '%s\n' "$TARGET_ROOT"
