package mr.natijati.results.exam;

import mr.natijati.results.result.BacResultRepository;
import mr.natijati.results.result.BepcResultRepository;
import mr.natijati.results.result.C1asResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamSessionService {

    private final ExamSessionRepository sessionRepository;
    private final BacResultRepository bacResultRepository;
    private final BepcResultRepository bepcResultRepository;
    private final C1asResultRepository c1asResultRepository;

    public ExamSessionService(
        ExamSessionRepository sessionRepository,
        BacResultRepository bacResultRepository,
        BepcResultRepository bepcResultRepository,
        C1asResultRepository c1asResultRepository
    ) {
        this.sessionRepository = sessionRepository;
        this.bacResultRepository = bacResultRepository;
        this.bepcResultRepository = bepcResultRepository;
        this.c1asResultRepository = c1asResultRepository;
    }

    @Transactional
    public ExamSession prepareSession(
        ExamType examType,
        int year,
        SessionType sessionType,
        String filename
    ) {
        ExamSession session = sessionRepository
            .findByExamTypeAndYearAndSessionType(examType, year, sessionType)
            .orElseGet(() -> ExamSession.create(examType, year, sessionType, filename));

        if (session.getStatus() == PublicationStatus.PUBLISHED) {
            throw new IllegalStateException(
                "Cette session est déjà publiée. Une correction devra utiliser "
                    + "un mécanisme de versionnement."
            );
        }

        session.prepareForImport(filename);
        ExamSession saved = sessionRepository.save(session);
        deleteExistingResults(saved);
        return saved;
    }

    @Transactional
    public void markImporting(Long sessionId) {
        ExamSession session = getRequired(sessionId);
        session.markImporting();
    }

    @Transactional
    public void markReady(Long sessionId) {
        ExamSession session = getRequired(sessionId);
        session.markReady(countResults(session));
    }

    @Transactional
    public void markFailed(Long sessionId) {
        ExamSession session = getRequired(sessionId);
        session.markFailed();
    }

    @Transactional
    public ExamSession publish(Long sessionId) {
        ExamSession session = getRequired(sessionId);
        if (session.getStatus() != PublicationStatus.READY) {
            throw new IllegalStateException(
                "La session doit être READY avant publication. Statut actuel : "
                    + session.getStatus()
            );
        }
        session.publish();
        return session;
    }

    @Transactional(readOnly = true)
    public ExamSession getRequired(Long sessionId) {
        return sessionRepository.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Session introuvable : " + sessionId
            ));
    }

    private void deleteExistingResults(ExamSession session) {
        switch (session.getExamType()) {
            case BAC -> bacResultRepository.deleteByExamSession_Id(session.getId());
            case BEPC -> bepcResultRepository.deleteByExamSession_Id(session.getId());
            case C1AS -> c1asResultRepository.deleteByExamSession_Id(session.getId());
        }
    }

    private long countResults(ExamSession session) {
        return switch (session.getExamType()) {
            case BAC -> bacResultRepository.countByExamSession_Id(session.getId());
            case BEPC -> bepcResultRepository.countByExamSession_Id(session.getId());
            case C1AS -> c1asResultRepository.countByExamSession_Id(session.getId());
        };
    }
}
