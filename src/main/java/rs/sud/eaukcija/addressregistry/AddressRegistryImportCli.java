package rs.sud.eaukcija.addressregistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import rs.sud.eaukcija.SudAukcijeApplication;

/** Dedicated non-web operator entrypoint used by the Gradle import task. */
public final class AddressRegistryImportCli {

    private AddressRegistryImportCli() {
    }

    public static void main(String[] args) throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SudAukcijeApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)) {
            AddressRegistryImportProperties properties = context.getBean(AddressRegistryImportProperties.class);
            AddressRegistryImporter importer = context.getBean(AddressRegistryImporter.class);
            Object result = switch (properties.getAction()) {
                case IMPORT -> importer.importSnapshot(properties);
                case ROLLBACK -> importer.rollback();
                case STATUS -> importer.status();
            };
            System.out.println(context.getBean(ObjectMapper.class).writerWithDefaultPrettyPrinter().writeValueAsString(result));
        }
    }
}
