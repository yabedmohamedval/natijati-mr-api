package mr.natijati.results.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class AdminConfigurationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
            SecurityAutoConfiguration.class, WebMvcAutoConfiguration.class))
        .withUserConfiguration(SecurityConfiguration.class)
        .withInitializer(context -> {
            // Never inherit local credentials. No datasource auto-configuration is loaded.
            var sources = context.getEnvironment().getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            try {
                for (var source : new YamlPropertySourceLoader().load("application",
                    new ClassPathResource("application.yml"))) {
                    sources.addLast(source);
                }
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
            ConfigurationPropertySources.attach(context.getEnvironment());
        });

    @Test
    void missingAdminPasswordPreventsStartup() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining(
                "Could not resolve placeholder 'ADMIN_PASSWORD'");
        });
    }

    @Test
    void explicitSyntheticPasswordCreatesAdmin() {
        runner.withPropertyValues("ADMIN_PASSWORD=synthetic-config-test-only").run(context -> {
            assertThat(context).hasNotFailed();
            var admin = context.getBean(UserDetailsService.class).loadUserByUsername("admin");
            assertThat(context.getBean(PasswordEncoder.class)
                .matches("synthetic-config-test-only", admin.getPassword())).isTrue();
            assertThat(admin.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        });
    }
}
