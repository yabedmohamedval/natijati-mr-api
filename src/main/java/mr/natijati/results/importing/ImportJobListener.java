package mr.natijati.results.importing;

import mr.natijati.results.exam.ExamSessionService;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

public class ImportJobListener implements JobExecutionListener {

    private final ExamSessionService sessionService;

    public ImportJobListener(ExamSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        Long sessionId = jobExecution.getJobParameters().getLong("sessionId");
        sessionService.markImporting(sessionId);
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        Long sessionId = jobExecution.getJobParameters().getLong("sessionId");

        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            sessionService.markReady(sessionId);
        } else {
            sessionService.markFailed(sessionId);
        }
    }
}
