package rs.sud.eaukcija.basemap;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict single-range parser for PMTiles byte serving. */
public record HttpByteRange(long start, long end) {

    private static final Pattern BYTE_RANGE = Pattern.compile("([0-9]*)-([0-9]*)");

    /**
     * Parses a supported single byte range. An empty result means RFC 9110
     * requires or permits the server to ignore the Range field and send 200.
     */
    public static Optional<HttpByteRange> parse(String header, long resourceSize) {
        if (header == null || header.isBlank() || resourceSize <= 0) {
            throw new IllegalArgumentException("invalid byte range");
        }
        String trimmed = header.trim();
        int separator = trimmed.indexOf('=');
        if (separator <= 0) {
            throw new IllegalArgumentException("invalid range field");
        }
        String unit = trimmed.substring(0, separator).trim();
        if (!unit.equalsIgnoreCase("bytes")) {
            return Optional.empty();
        }
        String[] ranges = trimmed.substring(separator + 1).split(",", -1);
        if (ranges.length > 1) {
            boolean satisfiable = false;
            for (String range : ranges) {
                try {
                    parseSingle(range.trim(), resourceSize);
                    satisfiable = true;
                } catch (UnsatisfiableRangeException exception) {
                    // RFC 9110 defines a byte-range-set as satisfiable when at
                    // least one member overlaps the selected representation.
                }
            }
            if (satisfiable) {
                // Multipart/byteranges is intentionally unsupported. Ignoring
                // a satisfiable Range field and sending the full 200 is valid.
                return Optional.empty();
            }
            throw new IllegalArgumentException("byte ranges do not overlap the resource");
        }
        return Optional.of(parseSingle(ranges[0].trim(), resourceSize));
    }

    private static HttpByteRange parseSingle(String value, long resourceSize) {
        Matcher matcher = BYTE_RANGE.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid byte range syntax");
        }
        String startText = matcher.group(1);
        String endText = matcher.group(2);
        if (startText.isEmpty() && endText.isEmpty()) {
            throw new IllegalArgumentException("empty byte range");
        }

        try {
            if (startText.isEmpty()) {
                long suffixLength = Long.parseLong(endText);
                if (suffixLength <= 0) {
                    throw new UnsatisfiableRangeException("invalid suffix byte range");
                }
                long returnedLength = Math.min(suffixLength, resourceSize);
                return new HttpByteRange(resourceSize - returnedLength, resourceSize - 1);
            }

            long start = Long.parseLong(startText);
            if (start < 0 || start >= resourceSize) {
                throw new UnsatisfiableRangeException("byte range starts beyond the resource");
            }
            long end = endText.isEmpty()
                    ? resourceSize - 1
                    : Math.min(Long.parseLong(endText), resourceSize - 1);
            if (end < start) {
                throw new UnsatisfiableRangeException("byte range end precedes its start");
            }
            return new HttpByteRange(start, end);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("byte range number is invalid", exception);
        }
    }

    public long length() {
        return end - start + 1;
    }

    private static final class UnsatisfiableRangeException extends IllegalArgumentException {

        private UnsatisfiableRangeException(String message) {
            super(message);
        }
    }
}
