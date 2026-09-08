package rs.sud.eaukcija.filter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

import org.springframework.core.io.ClassPathResource;

/** Pinned public RGZ municipality names; no request-time artifact/network dependency. */
public final class SerbiaMunicipalities {
    private static final List<String> NAMES = load();
    private SerbiaMunicipalities() {}
    public static List<String> names() { return NAMES; }

    private static List<String> load() {
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("catalogue/serbia-municipalities.tsv").getInputStream(), StandardCharsets.UTF_8))) {
            var codes = new HashSet<String>();
            return reader.lines().map(line -> {
                String[] fields = line.split("\t", -1);
                if (fields.length != 2 || !fields[0].matches("[0-9]{5}") || !codes.add(fields[0])
                        || !AuctionFilterParser.safeLabel(fields[1])) {
                    throw new IllegalStateException("Invalid packaged municipality catalogue");
                }
                return fields[1];
            }).toList();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read packaged municipality catalogue", failure);
        }
    }
}
