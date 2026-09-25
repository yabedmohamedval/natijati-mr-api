package mr.natijati.results.result;

import mr.natijati.results.exam.ExamType;
import mr.natijati.results.exam.SessionType;
import mr.natijati.results.exam.PublicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface C1asResultRepository extends JpaRepository<C1asResult, Long> {

    Optional<C1asResult> findByExamSession_ExamTypeAndExamSession_YearAndExamSession_SessionTypeAndExamSession_StatusAndDossierNumber(
        ExamType examType,
        Integer year,
        SessionType sessionType,
        PublicationStatus status,
        Long dossierNumber
    );

    long countByExamSession_Id(Long sessionId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from C1asResult r where r.examSession.id = :sessionId")
    void deleteByExamSession_Id(@org.springframework.data.repository.query.Param("sessionId") Long sessionId);
}
