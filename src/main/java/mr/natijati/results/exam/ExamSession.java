package mr.natijati.results.exam;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(
    name = "exam_session",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_exam_session",
        columnNames = {"exam_type", "exam_year", "session_type"}
    )
)
public class ExamSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "exam_type", nullable = false, length = 20)
    private ExamType examType;

    @Column(name = "exam_year", nullable = false)
    private Integer year;

    @Enumerated(EnumType.STRING)
    @Column(name = "session_type", nullable = false, length = 30)
    private SessionType sessionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PublicationStatus status;

    @Column(name = "source_filename")
    private String sourceFilename;

    @Column(name = "result_count", nullable = false)
    private long resultCount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    protected ExamSession() {
    }

    public static ExamSession create(
        ExamType examType,
        Integer year,
        SessionType sessionType,
        String sourceFilename
    ) {
        ExamSession session = new ExamSession();
        session.examType = examType;
        session.year = year;
        session.sessionType = sessionType;
        session.sourceFilename = sourceFilename;
        session.status = PublicationStatus.DRAFT;
        session.createdAt = OffsetDateTime.now();
        return session;
    }

    public void prepareForImport(String filename) {
        this.sourceFilename = filename;
        this.resultCount = 0;
        this.status = PublicationStatus.DRAFT;
    }

    public void markImporting() {
        this.status = PublicationStatus.IMPORTING;
    }

    public void markReady(long resultCount) {
        this.status = PublicationStatus.READY;
        this.resultCount = resultCount;
    }

    public void markFailed() {
        this.status = PublicationStatus.FAILED;
        this.resultCount = 0;
    }

    public void publish() {
        this.status = PublicationStatus.PUBLISHED;
        this.publishedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public ExamType getExamType() {
        return examType;
    }

    public Integer getYear() {
        return year;
    }

    public SessionType getSessionType() {
        return sessionType;
    }

    public PublicationStatus getStatus() {
        return status;
    }

    public String getSourceFilename() {
        return sourceFilename;
    }

    public long getResultCount() {
        return resultCount;
    }
}
