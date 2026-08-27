package mr.natijati.results.result;

import jakarta.persistence.*;
import mr.natijati.results.exam.DecisionCode;
import mr.natijati.results.exam.ExamSession;
import mr.natijati.results.importing.BacResultCommand;

import java.math.BigDecimal;

@Entity
@Table(name = "bac_result")
public class BacResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_session_id", nullable = false)
    private ExamSession examSession;

    @Column(name = "candidate_number", nullable = false)
    private Long candidateNumber;

    @Column(name = "name_fr")
    private String nameFr;

    @Column(name = "name_ar")
    private String nameAr;

    @Column(name = "wilaya_fr")
    private String wilayaFr;

    @Column(name = "wilaya_ar")
    private String wilayaAr;

    @Column(name = "exam_center_fr")
    private String examCenterFr;

    @Column(name = "exam_center_ar")
    private String examCenterAr;

    @Column(name = "institution_fr")
    private String institutionFr;

    @Column(name = "institution_ar")
    private String institutionAr;

    @Column(name = "series_code")
    private String seriesCode;

    @Column(name = "series_fr")
    private String seriesFr;

    @Column(name = "series_ar")
    private String seriesAr;

    @Column(precision = 18, scale = 12)
    private BigDecimal score;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_code")
    private DecisionCode decisionCode;

    @Column(name = "decision_original")
    private String decisionOriginal;

    protected BacResult() {
    }

    public static BacResult from(ExamSession session, BacResultCommand command) {
        BacResult result = new BacResult();
        result.examSession = session;
        result.candidateNumber = command.candidateNumber();
        result.nameFr = command.nameFr();
        result.nameAr = command.nameAr();
        result.wilayaFr = command.wilayaFr();
        result.wilayaAr = command.wilayaAr();
        result.examCenterFr = command.examCenterFr();
        result.examCenterAr = command.examCenterAr();
        result.institutionFr = command.institutionFr();
        result.institutionAr = command.institutionAr();
        result.seriesCode = command.seriesCode();
        result.seriesFr = command.seriesFr();
        result.seriesAr = command.seriesAr();
        result.score = command.score();
        result.decisionCode = command.decisionCode();
        result.decisionOriginal = command.decisionOriginal();
        return result;
    }

    public Long getCandidateNumber() { return candidateNumber; }
    public String getNameFr() { return nameFr; }
    public String getNameAr() { return nameAr; }
    public String getWilayaFr() { return wilayaFr; }
    public String getWilayaAr() { return wilayaAr; }
    public String getExamCenterFr() { return examCenterFr; }
    public String getExamCenterAr() { return examCenterAr; }
    public String getInstitutionFr() { return institutionFr; }
    public String getInstitutionAr() { return institutionAr; }
    public String getSeriesCode() { return seriesCode; }
    public String getSeriesFr() { return seriesFr; }
    public String getSeriesAr() { return seriesAr; }
    public BigDecimal getScore() { return score; }
    public DecisionCode getDecisionCode() { return decisionCode; }
    public String getDecisionOriginal() { return decisionOriginal; }
}
