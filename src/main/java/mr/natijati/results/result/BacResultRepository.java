package mr.natijati.results.result;

import mr.natijati.results.exam.ExamType;
import mr.natijati.results.exam.SessionType;
import mr.natijati.results.exam.PublicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BacResultRepository extends JpaRepository<BacResult, Long> {

    Optional<BacResult> findByExamSession_ExamTypeAndExamSession_YearAndExamSession_SessionTypeAndExamSession_StatusAndCandidateNumber(
        ExamType examType,
        Integer year,
        SessionType sessionType,
        PublicationStatus status,
        Long candidateNumber
    );

    long countByExamSession_Id(Long sessionId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from BacResult r where r.examSession.id = :sessionId")
    void deleteByExamSession_Id(@org.springframework.data.repository.query.Param("sessionId") Long sessionId);
}
