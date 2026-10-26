package mr.natijati.results.importing;

import mr.natijati.results.importing.async.*;
import mr.natijati.results.exam.ExamSessionRepository;
import mr.natijati.results.exam.ExamSessionService;
import mr.natijati.results.exam.ExamType;
import mr.natijati.results.result.BacResultRepository;
import mr.natijati.results.result.BepcResultRepository;
import mr.natijati.results.result.C1asResultRepository;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;

@Configuration
public class BatchImportConfiguration {

    @Bean
    @StepScope
    public ItemStreamReader<RawExamRow> excelResultItemReader(
        @Value("#{jobParameters['filePath']}") String filePath,
        @Value("#{jobParameters['examType']}") String examType,
        @Value("${app.import-max-rows}") int maxRows
    ) {
        return new ExcelResultItemReader(
            Path.of(filePath),
            ExamType.valueOf(examType), maxRows
        );
    }

    @Bean
    @StepScope
    public ItemProcessor<RawExamRow, ExamResultCommand> examResultProcessor(
        @Value("#{jobParameters['examType']}") String examType
    ) {
        return new ExamResultProcessor(ExamType.valueOf(examType));
    }

    @Bean
    @StepScope
    public ItemWriter<ExamResultCommand> examResultWriter(
        @Value("#{jobParameters['sessionId']}") Long sessionId,
        ExamSessionRepository sessionRepository,
        BacResultRepository bacResultRepository,
        BepcResultRepository bepcResultRepository,
        C1asResultRepository c1asResultRepository,
        ImportExecutionRepository executions,
        @Value("#{jobParameters['importId']}") String importId,
        @Value("#{jobParameters['importAttempt']}") Long importAttempt
    ) {
        var writer = new ExamResultWriter(
            sessionId,
            sessionRepository,
            bacResultRepository,
            bepcResultRepository,
            c1asResultRepository
        );
        return chunk -> {
            writer.write(chunk);
            if (importId != null) executions.progress(java.util.UUID.fromString(importId), importAttempt.intValue(), chunk.size());
        };
    }

    @Bean
    public Step importExamResultsStep(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        ItemStreamReader<RawExamRow> excelResultItemReader,
        ItemProcessor<RawExamRow, ExamResultCommand> examResultProcessor,
        ItemWriter<ExamResultCommand> examResultWriter,
        ImportExecutionRepository executions
    ) {
        return new StepBuilder("importExamResultsStep", jobRepository)
            .<RawExamRow, ExamResultCommand>chunk(500, transactionManager)
            .reader(excelResultItemReader)
            .processor(examResultProcessor)
            .writer(examResultWriter)
            .listener(new ImportProgressListener(executions))
            .build();
    }

    @Bean
    public Job examResultImportJob(
        JobRepository jobRepository,
        Step importExamResultsStep,
        ExamSessionService sessionService,
        ImportWorkerTransactions transactions
    ) {
        return new JobBuilder("examResultImportJob", jobRepository)
            .preventRestart()
            .listener(new ImportJobListener(sessionService, transactions))
            .start(importExamResultsStep)
            .build();
    }
}
