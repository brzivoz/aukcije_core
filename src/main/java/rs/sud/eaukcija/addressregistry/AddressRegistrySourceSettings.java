package rs.sud.eaukcija.addressregistry;

import java.net.URI;
import java.nio.file.Path;

/** Shared, read-only source/staging contract for Address Registry pipelines. */
interface AddressRegistrySourceSettings {

    URI getSourceUri();

    String getExpectedSha256();

    String getExpectedGpkgSha256();

    String getExpectedSchemaSha256();

    long getMinimumRows();

    long getMaximumRows();

    long getMinimumFreeBytes();

    long getMaximumGpkgBytes();

    Path getWorkDirectory();
}
