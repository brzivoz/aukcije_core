package rs.sud.eaukcija.basemap;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict single-range parser for PMTiles byte serving. */
public record HttpByteRange(long start, long end) {

    private static final Pattern SINGLE_RANGE = Pattern.compile("bytes=([0-9]*)-([0-9]*)");

    public static HttpByteRange parse(String header, long resourceSize) {
        if (header == null || header.isBlank() || resourceSize <= 0) {
            throw new IllegalArgumentException("invalid byte range");
        }
        Matcher matcher = SINGLE_RANGE.matcher(header.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("only one valid bytes range is supported");
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
                    throw new IllegalArgumentException("invalid suffix byte range");
                }
                long returnedLength = Math.min(suffixLength, resourceSize);
                return new HttpByteRange(resourceSize - returnedLength, resourceSize - 1);
            }

            long start = Long.parseLong(startText);
            if (start < 0 || start >= resourceSize) {
                throw new IllegalArgumentException("byte range starts beyond the resource");
            }
            long end = endText.isEmpty()
                    ? resourceSize - 1
                    : Math.min(Long.parseLong(endText), resourceSize - 1);
            if (end < start) {
                throw new IllegalArgumentException("byte range end precedes its start");
            }
            return new HttpByteRange(start, end);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("byte range number is invalid", exception);
        }
    }

    public long length() {
        return end - start + 1;
    }
}
