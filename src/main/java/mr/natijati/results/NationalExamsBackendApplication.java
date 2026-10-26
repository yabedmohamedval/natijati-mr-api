package mr.natijati.results;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NationalExamsBackendApplication {

    public static void main(String[] args) {
        var application = new SpringApplication(NationalExamsBackendApplication.class);
        boolean worker = "import-worker".equals(System.getenv("APP_MODE"));
        if (worker) application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        var context = application.run(args);
        if (worker) {
            int code = 1;
            try {
                code = context.getBean(mr.natijati.results.importing.async.ImportWorker.class)
                    .run(java.util.UUID.fromString(System.getenv("IMPORT_ID")), System.getenv("CLOUD_RUN_EXECUTION"));
            } catch (Exception failure) {
                org.slf4j.LoggerFactory.getLogger(NationalExamsBackendApplication.class).error("import_worker_exit_failed");
            } finally { context.close(); }
            System.exit(code);
        }
    }
}
