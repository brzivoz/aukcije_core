package rs.sud.eaukcija.enrichment;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class EnrichmentHashing {

    private EnrichmentHashing() {
    }

    public static String sha256(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                if (value == null) {
                    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
                } else {
                    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("JVM has no SHA-256 implementation", missing);
        }
    }
}
