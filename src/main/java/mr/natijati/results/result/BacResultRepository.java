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

    void deleteByExamSession_Id(Long sessionId);
}
