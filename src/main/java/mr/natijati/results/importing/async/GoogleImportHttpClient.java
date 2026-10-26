package mr.natijati.results.importing.async;

import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;

/** Official REST APIs + official ADC only; credentials are resolved lazily, never in tests. */
@Component
public class GoogleImportHttpClient {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private GoogleCredentials credentials;
    private synchronized String token() throws IOException {
        if (credentials==null) credentials=GoogleCredentials.getApplicationDefault()
            .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }
    public HttpResponse<String> sendJson(URI uri, String body) throws IOException {
        return send(uri, "POST", HttpRequest.BodyPublishers.ofString(body), HttpResponse.BodyHandlers.ofString(), "application/json");
    }
    public <T> HttpResponse<T> send(URI uri, String method, HttpRequest.BodyPublisher body,
        HttpResponse.BodyHandler<T> handler) throws IOException {
        return send(uri,method,body,handler,"application/octet-stream");
    }
    private <T> HttpResponse<T> send(URI uri, String method, HttpRequest.BodyPublisher body,
        HttpResponse.BodyHandler<T> handler, String contentType) throws IOException {
        try {
            return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .header("Authorization","Bearer "+token()).header("Content-Type", contentType)
                .method(method,body).build(),handler);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Requête cloud interrompue"); }
        catch (IOException failure) { throw new IOException("Service cloud indisponible"); }
    }
}
