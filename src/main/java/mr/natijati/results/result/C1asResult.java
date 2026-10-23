package mr.natijati.results.result;

import jakarta.persistence.*;
import mr.natijati.results.exam.ExamSession;
import mr.natijati.results.importing.C1asResultCommand;

import java.math.BigDecimal;

@Entity
@Table(name = "c1as_result")
public class C1asResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_session_id", nullable = false)
    private ExamSession examSession;

    @Column(name = "candidate_number")
    private Long candidateNumber;

    @Column(name = "dossier_number", nullable = false)
    private Long dossierNumber;

    @Column(name = "candidate_number_original")
    private String candidateNumberOriginal;

    @Column(name = "dossier_number_original")
    private String dossierNumberOriginal;

    @Column(name = "name_ar", nullable = false)
    private String nameAr;

    @Column(name = "candidate_type")
    private String candidateType;

    @Column(name = "school_ar")
    private String schoolAr;

    @Column(name = "wilaya_ar")
    private String wilayaAr;

    @Column(name = "moughataa_ar")
    private String moughataaAr;

    @Column(name = "exam_center_ar")
    private String examCenterAr;

    @Column(name = "total_score", precision = 18, scale = 12)
    private BigDecimal totalScore;

    protected C1asResult() {
    }

    public static C1asResult from(ExamSession session, C1asResultCommand command) {
        C1asResult result = new C1asResult();
        result.examSession = session;
        result.candidateNumber = command.candidateNumber();
        result.dossierNumber = command.dossierNumber();
        result.candidateNumberOriginal = command.candidateNumberOriginal();
        result.dossierNumberOriginal = command.dossierNumberOriginal();
        result.nameAr = command.nameAr();
        result.candidateType = command.candidateType();
        result.schoolAr = command.schoolAr();
        result.wilayaAr = command.wilayaAr();
        result.moughataaAr = command.moughataaAr();
        result.examCenterAr = command.examCenterAr();
        result.totalScore = command.totalScore();
        return result;
    }

    public Long getCandidateNumber() { return candidateNumber; }
    public Long getDossierNumber() { return dossierNumber; }
    public String getCandidateNumberOriginal() { return candidateNumberOriginal; }
    public String getDossierNumberOriginal() { return dossierNumberOriginal; }
    public String getNameAr() { return nameAr; }
    public String getCandidateType() { return candidateType; }
    public String getSchoolAr() { return schoolAr; }
    public String getWilayaAr() { return wilayaAr; }
    public String getMoughataaAr() { return moughataaAr; }
    public String getExamCenterAr() { return examCenterAr; }
    public BigDecimal getTotalScore() { return totalScore; }
}
