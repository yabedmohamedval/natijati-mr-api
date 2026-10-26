package mr.natijati.results.importing.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.util.*;

@Component
@ConditionalOnProperty(name="app.import-provider",havingValue="gcp",matchIfMissing=true)
public class CloudRunImportJobDispatcher implements ImportJobDispatcher {
    private final GoogleImportHttpClient http;
    private final ObjectMapper json;
    private final String job;
    public CloudRunImportJobDispatcher(GoogleImportHttpClient http, ObjectMapper json, @Value("${app.import-job-name:}") String job) {
        this.http=http;this.json=json;this.job=job;
    }
    public String dispatch(UUID id) throws IOException {
        if(!job.matches("projects/[a-zA-Z0-9-]+/locations/[a-z0-9-]+/jobs/[a-z0-9-]+")) throw new IOException("Job import non configuré");
        String body=json.writeValueAsString(Map.of("overrides",Map.of("containerOverrides",List.of(
            Map.of("env",List.of(Map.of("name","IMPORT_ID","value",id.toString())))))));
        var response=http.sendJson(URI.create("https://run.googleapis.com/v2/"+job+":run"),body);
        if(response.statusCode()<200 || response.statusCode()>=300) throw new IOException("Déclenchement Job non confirmé");
        String operation=json.readTree(response.body()).path("name").asText();
        if(operation.isBlank() || operation.length()>512) throw new IOException("Opération Job non confirmée");
        return operation;
    }
}
