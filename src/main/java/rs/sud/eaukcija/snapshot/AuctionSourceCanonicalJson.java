package rs.sud.eaukcija.snapshot;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** One fixed exact reader and canonical serializer for source snapshot evidence. */
public final class AuctionSourceCanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final ObjectReader TREE_READER = MAPPER.readerFor(JsonNode.class);
    private static final ObjectWriter WRITER = MAPPER.writer()
            .with(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private AuctionSourceCanonicalJson() {
    }

    /** Reads a source tree without routing decimal values through {@code double}. */
    public static JsonNode readTree(JsonParser parser) throws IOException {
        return TREE_READER.readValue(parser);
    }

    /** Reads stored or fixture source JSON with the same exact numeric contract. */
    public static JsonNode readTree(String value) throws JsonProcessingException {
        return TREE_READER.readValue(value);
    }

    /** Writes the exact canonical form used for hashing and PostgreSQL storage. */
    public static String write(JsonNode value) {
        try {
            return WRITER.writeValueAsString(orderObjectKeys(value));
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("source snapshot cannot be canonicalized");
        }
    }

    static byte[] bytes(JsonNode value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    static String sha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(bytes(value)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static JsonNode orderObjectKeys(JsonNode value) {
        if (value.isObject()) {
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            ObjectNode ordered = MAPPER.createObjectNode();
            for (String name : names) {
                ordered.set(name, orderObjectKeys(value.get(name)));
            }
            return ordered;
        }
        if (value.isArray()) {
            ArrayNode ordered = MAPPER.createArrayNode();
            value.forEach(element -> ordered.add(orderObjectKeys(element)));
            return ordered;
        }
        return value.deepCopy();
    }
}
