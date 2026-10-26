package mr.natijati.results.integration;

import mr.natijati.results.importing.async.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

@TestConfiguration
public class AsyncImportTestConfiguration {
    @Bean ImportObjectStorage importObjectStorage(org.springframework.core.env.Environment environment) throws IOException {
        String configured=environment.getProperty("test.object-directory");
        return new FileSystemImportObjectStorage(configured==null ? Files.createTempDirectory("import-objects-test-") : Path.of(configured));
    }
    @Bean FakeImportJobDispatcher importJobDispatcher() { return new FakeImportJobDispatcher(); }
    public static class FakeImportJobDispatcher implements ImportJobDispatcher {
        public final List<UUID> dispatched=new java.util.concurrent.CopyOnWriteArrayList<>();
        public boolean fail;
        public String dispatch(UUID id) throws IOException {
            dispatched.add(id);
            if(fail)throw new IOException("Synthetic dispatch outage");
            return "operations/synthetic-"+id;
        }
    }
}
