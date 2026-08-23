package rs.sud.eaukcija.map;

/** A client-correctable map request error with a stable field identifier. */
public class InvalidMapRequestException extends RuntimeException {

    private final String field;

    public InvalidMapRequestException(String field, String message) {
        super(message);
        this.field = boundedField(field);
    }

    public String field() {
        return field;
    }

    private static String boundedField(String field) {
        if (field == null || field.isBlank()) {
            return "query";
        }
        StringBuilder bounded = new StringBuilder(Math.min(field.length(), 64));
        for (int offset = 0, count = 0; offset < field.length() && count < 64; count++) {
            int codePoint = field.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT) {
                bounded.appendCodePoint(codePoint);
            }
        }
        return bounded.isEmpty() ? "query" : bounded.toString();
    }
}
