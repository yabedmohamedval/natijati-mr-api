package mr.natijati.results.exam;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;


public interface ExamSessionRepository extends JpaRepository<ExamSession, Long> {

    Optional<ExamSession> findByExamTypeAndYearAndSessionType(
        ExamType examType,
        Integer year,
        SessionType sessionType
    );
}
