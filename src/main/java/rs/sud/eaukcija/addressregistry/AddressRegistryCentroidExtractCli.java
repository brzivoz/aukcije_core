package rs.sud.eaukcija.addressregistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Database-free operator entrypoint for the versioned centroid extract. */
public final class AddressRegistryCentroidExtractCli {

    private AddressRegistryCentroidExtractCli() {
    }

    public static void main(String[] args) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        if (args.length > 0) {
            environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        }
        AddressRegistryCentroidExtractProperties properties = Binder.get(environment)
                .bind("address-registry.centroid-extract",
                        Bindable.of(AddressRegistryCentroidExtractProperties.class))
                .orElseGet(AddressRegistryCentroidExtractProperties::new);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AddressRegistryCentroidExtractor extractor = new AddressRegistryCentroidExtractor(
                new AddressRegistryArtifactStager(), new GeoPackageInspector(), objectMapper);
        Object result = switch (properties.getAction()) {
            case BUILD -> extractor.build(properties);
            case STATUS -> extractor.status(properties.getPublishDirectory());
        };
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
}
