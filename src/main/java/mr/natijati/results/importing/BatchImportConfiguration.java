package mr.natijati.results.importing;

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
        @Value("#{jobParameters['examType']}") String examType
    ) {
        return new ExcelResultItemReader(
            Path.of(filePath),
            ExamType.valueOf(examType)
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
        C1asResultRepository c1asResultRepository
    ) {
        return new ExamResultWriter(
            sessionId,
            sessionRepository,
            bacResultRepository,
            bepcResultRepository,
            c1asResultRepository
        );
    }

    @Bean
    public Step importExamResultsStep(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        ItemStreamReader<RawExamRow> excelResultItemReader,
        ItemProcessor<RawExamRow, ExamResultCommand> examResultProcessor,
        ItemWriter<ExamResultCommand> examResultWriter
    ) {
        return new StepBuilder("importExamResultsStep", jobRepository)
            .<RawExamRow, ExamResultCommand>chunk(500, transactionManager)
            .reader(excelResultItemReader)
            .processor(examResultProcessor)
            .writer(examResultWriter)
            .build();
    }

    @Bean
    public Job examResultImportJob(
        JobRepository jobRepository,
        Step importExamResultsStep,
        ExamSessionService sessionService
    ) {
        return new JobBuilder("examResultImportJob", jobRepository)
            .listener(new ImportJobListener(sessionService))
            .start(importExamResultsStep)
            .build();
    }
}
