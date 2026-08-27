package mr.natijati.results.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI nationalExamsOpenApi() {
        return new OpenAPI().info(
            new Info()
                .title("National Exams Mauritania API")
                .version("1.0.0")
                .description(
                    "Import et consultation des résultats BAC, BEPC et C1AS"
                )
        );
    }
}
