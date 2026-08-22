package rs.sud.eaukcija.spatial;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Concurrency-safe access to the globally normalized KO-code plus parcel-number identity. */
@Repository
@Profile("!local-h2")
public class ParcelIdentityRepository {

    private final JdbcTemplate jdbc;

    public ParcelIdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ParcelIdentity getOrCreate(String rawKoCode, String rawParcelNumber) {
        String koCode = ParcelIdentityNormalizer.canonicalKoCode(rawKoCode);
        String parcelNumber = ParcelIdentityNormalizer.canonicalParcelNumber(rawParcelNumber);
        List<ParcelIdentity> inserted = jdbc.query("""
                INSERT INTO parcel_identities (ko_code, canonical_parcel_number)
                VALUES (?, ?)
                ON CONFLICT (ko_code, canonical_parcel_number) DO NOTHING
                RETURNING id, ko_code, canonical_parcel_number
                """, ParcelIdentityRepository::mapIdentity,
                koCode, parcelNumber);
        if (!inserted.isEmpty()) {
            return inserted.get(0);
        }
        return jdbc.queryForObject("""
                SELECT id, ko_code, canonical_parcel_number
                  FROM parcel_identities
                 WHERE ko_code = ? AND canonical_parcel_number = ?
                """, ParcelIdentityRepository::mapIdentity, koCode, parcelNumber);
    }

    private static ParcelIdentity mapIdentity(ResultSet rs, int rowNum) throws SQLException {
        return new ParcelIdentity(
                rs.getLong("id"),
                rs.getString("ko_code"),
                rs.getString("canonical_parcel_number"));
    }

    public record ParcelIdentity(long id, String koCode, String canonicalParcelNumber) {
    }
}
