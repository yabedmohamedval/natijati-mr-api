package mr.natijati.results.result;

import jakarta.persistence.*;
import mr.natijati.results.exam.DecisionCode;
import mr.natijati.results.exam.ExamSession;
import mr.natijati.results.importing.BepcResultCommand;

import java.math.BigDecimal;

@Entity
@Table(name = "bepc_result")
public class BepcResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_session_id", nullable = false)
    private ExamSession examSession;

    @Column(name = "candidate_number", nullable = false)
    private Long candidateNumber;

    @Column(name = "candidate_number_original")
    private String candidateNumberOriginal;

    @Column(name = "name_fr", nullable = false)
    private String nameFr;

    @Column(name = "wilaya_code")
    private Integer wilayaCode;

    @Column(name = "wilaya_fr")
    private String wilayaFr;

    @Column(name = "exam_center_fr")
    private String examCenterFr;

    @Column(precision = 24, scale = 18)
    private BigDecimal score;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_code")
    private DecisionCode decisionCode;

    @Column(name = "decision_original")
    private String decisionOriginal;

    protected BepcResult() {
    }

    public static BepcResult from(ExamSession session, BepcResultCommand command) {
        BepcResult result = new BepcResult();
        result.examSession = session;
        result.candidateNumber = command.candidateNumber();
        result.candidateNumberOriginal = command.candidateNumberOriginal();
        result.nameFr = command.nameFr();
        result.wilayaCode = command.wilayaCode();
        result.wilayaFr = command.wilayaFr();
        result.examCenterFr = command.examCenterFr();
        result.score = command.score();
        result.decisionCode = command.decisionCode();
        result.decisionOriginal = command.decisionOriginal();
        return result;
    }

    public Long getCandidateNumber() { return candidateNumber; }
    public String getCandidateNumberOriginal() { return candidateNumberOriginal; }
    public String getNameFr() { return nameFr; }
    public Integer getWilayaCode() { return wilayaCode; }
    public String getWilayaFr() { return wilayaFr; }
    public String getExamCenterFr() { return examCenterFr; }
    public BigDecimal getScore() { return score; }
    public DecisionCode getDecisionCode() { return decisionCode; }
    public String getDecisionOriginal() { return decisionOriginal; }
}
