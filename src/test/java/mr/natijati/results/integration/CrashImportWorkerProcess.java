package mr.natijati.results.integration;

import mr.natijati.results.NationalExamsBackendApplication;
import mr.natijati.results.importing.async.ImportWorker;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import java.util.UUID;

/** Separate JVM killed after a REAL fourth commit: no listeners/finally/shutdown hooks run. */
public class CrashImportWorkerProcess {
    public static void main(String[] args) throws Exception {
        var app=new SpringApplication(NationalExamsBackendApplication.class,AsyncImportTestConfiguration.class,CrashConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try(var context=app.run(args)) {
            context.getBean(ImportWorker.class).run(UUID.fromString(context.getEnvironment().getRequiredProperty("test.import-id")),"synthetic-crashed-execution");
        }
        throw new AssertionError("Worker should have halted");
    }
    @TestConfiguration
    static class CrashConfiguration {
        @Bean static BeanPostProcessor haltAfterFourthCommit() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if(bean instanceof TaskletStep step)step.registerChunkListener(new ChunkListener() {
                        @Override public void afterChunk(ChunkContext context) {
                            if(context.getStepContext().getStepExecution().getWriteCount()==2000) Runtime.getRuntime().halt(137);
                        }
                    });
                    return bean;
                }
            };
        }
    }
}
