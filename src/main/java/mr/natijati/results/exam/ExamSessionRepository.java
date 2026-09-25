package mr.natijati.results.exam;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;


public interface ExamSessionRepository extends JpaRepository<ExamSession, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from ExamSession s where s.id = :id")
    Optional<ExamSession> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<ExamSession> findByExamTypeAndYearAndSessionType(
        ExamType examType,
        Integer year,
        SessionType sessionType
    );
}
