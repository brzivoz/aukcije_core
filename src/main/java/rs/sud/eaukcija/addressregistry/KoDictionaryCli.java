package rs.sud.eaukcija.addressregistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Database-free operator entrypoint for the immutable canonical KO dictionary. */
public final class KoDictionaryCli {

    private KoDictionaryCli() {
    }

    public static void main(String[] args) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        if (args.length > 0) {
            environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        }
        KoDictionaryProperties properties = Binder.get(environment)
                .bind("address-registry.ko-dictionary", Bindable.of(KoDictionaryProperties.class))
                .orElseGet(KoDictionaryProperties::new);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        KoDictionaryPublisher publisher = new KoDictionaryPublisher(objectMapper);
        Object result = switch (properties.getAction()) {
            case BUILD -> publisher.build(properties);
            case STATUS -> publisher.status(properties.getPublishDirectory());
        };
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
}
