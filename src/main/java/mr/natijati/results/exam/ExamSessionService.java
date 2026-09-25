package mr.natijati.results.exam;

import mr.natijati.results.result.BacResultRepository;
import mr.natijati.results.result.BepcResultRepository;
import mr.natijati.results.result.C1asResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamSessionService {

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ExamSessionRepository sessionRepository;
    private final BacResultRepository bacResultRepository;
    private final BepcResultRepository bepcResultRepository;
    private final C1asResultRepository c1asResultRepository;

    public ExamSessionService(
        org.springframework.jdbc.core.JdbcTemplate jdbc,
        ExamSessionRepository sessionRepository,
        BacResultRepository bacResultRepository,
        BepcResultRepository bepcResultRepository,
        C1asResultRepository c1asResultRepository
    ) {
        this.jdbc = jdbc;
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
        if (year < 1900 || year > 2100) throw new IllegalArgumentException("Année hors plage 1900–2100");
        // Transaction-scoped lock works with PostgreSQL transaction poolers, including a new session.
        Boolean acquired = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class,
            examType.name() + ":" + year + ":" + sessionType.name());
        if (!Boolean.TRUE.equals(acquired)) throw new IllegalStateException("Un import réserve déjà cette session");
        ExamSession session = sessionRepository
            .findByExamTypeAndYearAndSessionType(examType, year, sessionType)
            .orElseGet(() -> ExamSession.create(examType, year, sessionType, filename));

        if (session.getStatus() == PublicationStatus.PUBLISHED || session.getStatus() == PublicationStatus.READY
            || session.getStatus() == PublicationStatus.IMPORTING) {
            throw new IllegalStateException(
                "Session déjà en cours, prête ou publiée : remplacement interdit"
            );
        }

        session.prepareForImport(filename);
        session.markImporting();
        ExamSession saved = sessionRepository.save(session);
        deleteExistingResults(saved);
        return saved;
    }

    @Transactional
    public void markImporting(Long sessionId) {
        ExamSession session = locked(sessionId);
        if (session.getStatus() != PublicationStatus.IMPORTING) throw new IllegalStateException("Session non réservée pour import");
    }

    @Transactional
    public void markReady(Long sessionId) {
        ExamSession session = locked(sessionId);
        long count = countResults(session);
        if (session.getStatus() != PublicationStatus.IMPORTING || count == 0)
            throw new IllegalStateException("Une session vide ou non IMPORTING ne peut pas devenir READY");
        session.markReady(count);
    }

    @Transactional
    public void markFailed(Long sessionId) {
        ExamSession session = locked(sessionId);
        if (session.getStatus() != PublicationStatus.IMPORTING && session.getStatus() != PublicationStatus.FAILED)
            throw new IllegalStateException("Nettoyage interdit pour cette session");
        deleteExistingResults(session);
        session.markFailed();
    }

    @Transactional
    public ExamSession publish(Long sessionId) {
        ExamSession session = locked(sessionId);
        if (session.getStatus() == PublicationStatus.PUBLISHED) return session;
        if (session.getStatus() != PublicationStatus.READY || session.getResultCount() <= 0 || countResults(session) != session.getResultCount()) {
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

    private ExamSession locked(Long id) {
        return sessionRepository.findLockedById(id).orElseThrow(() -> new IllegalArgumentException("Session introuvable : " + id));
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
