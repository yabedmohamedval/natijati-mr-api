package mr.natijati.results.importing.async;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

@Component
@ConditionalOnProperty(name="app.import-provider",havingValue="gcp",matchIfMissing=true)
public class GcsImportObjectStorage implements ImportObjectStorage {
    private final GoogleImportHttpClient http;
    private final String bucket;
    public GcsImportObjectStorage(GoogleImportHttpClient http, @Value("${app.import-bucket:}") String bucket) { this.http=http;this.bucket=bucket; }
    private String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    private URI uri(String key, boolean upload, String query) throws IOException {
        if (!bucket.matches("[a-z0-9][a-z0-9._-]{1,220}[a-z0-9]")) throw new IOException("Stockage import non configuré");
        ImportObjectStorage.validateKey(key);
        return URI.create("https://storage.googleapis.com/"+(upload ? "upload/" : "")+"storage/v1/b/"+encode(bucket)+"/o"+
            (upload ? "?uploadType=media&ifGenerationMatch=0&name="+encode(key) : "/"+encode(key)+query));
    }
    public void store(String key, Path source) throws IOException {
        var response=http.send(uri(key,true,""),"POST",HttpRequest.BodyPublishers.ofFile(source),HttpResponse.BodyHandlers.discarding());
        requireSuccess(response.statusCode());
    }
    public void download(String key, Path destination) throws IOException {
        // Response is streamed; never put the 50 MiB workbook in a byte array.
        var response=http.send(uri(key,false,"?alt=media"),"GET",HttpRequest.BodyPublishers.noBody(),HttpResponse.BodyHandlers.ofInputStream());
        try(var input=response.body()) {
            requireSuccess(response.statusCode());
            try(var output=Files.newOutputStream(destination)) {
                byte[] buffer=new byte[8192];long size=0;int length;
                while((length=input.read(buffer))!=-1) {
                    if ((size+=length)>50L*1024*1024) throw new IOException("Objet import hors limites");
                    output.write(buffer,0,length);
                }
            }
        }
    }
    public void delete(String key) throws IOException {
        var response=http.send(uri(key,false,""),"DELETE",HttpRequest.BodyPublishers.noBody(),HttpResponse.BodyHandlers.discarding());
        if(response.statusCode()!=404) requireSuccess(response.statusCode());
    }
    public boolean exists(String key) throws IOException {
        var response=http.send(uri(key,false,"?fields=name"),"GET",HttpRequest.BodyPublishers.noBody(),HttpResponse.BodyHandlers.discarding());
        if(response.statusCode()==404)return false;
        requireSuccess(response.statusCode());return true;
    }
    private void requireSuccess(int code) throws IOException { if(code<200 || code>=300)throw new IOException("Opération stockage impossible"); }
}
