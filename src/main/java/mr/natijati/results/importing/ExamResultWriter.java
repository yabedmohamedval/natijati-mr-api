package mr.natijati.results.importing;

import mr.natijati.results.exam.ExamSession;
import mr.natijati.results.exam.ExamSessionRepository;
import mr.natijati.results.result.*;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.util.ArrayList;
import java.util.List;

public class ExamResultWriter implements ItemWriter<ExamResultCommand> {

    private final Long sessionId;
    private final ExamSessionRepository sessionRepository;
    private final BacResultRepository bacResultRepository;
    private final BepcResultRepository bepcResultRepository;
    private final C1asResultRepository c1asResultRepository;

    public ExamResultWriter(
        Long sessionId,
        ExamSessionRepository sessionRepository,
        BacResultRepository bacResultRepository,
        BepcResultRepository bepcResultRepository,
        C1asResultRepository c1asResultRepository
    ) {
        this.sessionId = sessionId;
        this.sessionRepository = sessionRepository;
        this.bacResultRepository = bacResultRepository;
        this.bepcResultRepository = bepcResultRepository;
        this.c1asResultRepository = c1asResultRepository;
    }

    @Override
    public void write(Chunk<? extends ExamResultCommand> chunk) {
        ExamSession session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Session introuvable : " + sessionId
            ));

        List<BacResult> bacResults = new ArrayList<>();
        List<BepcResult> bepcResults = new ArrayList<>();
        List<C1asResult> c1asResults = new ArrayList<>();

        for (ExamResultCommand command : chunk.getItems()) {
            switch (command) {
                case BacResultCommand bac ->
                    bacResults.add(BacResult.from(session, bac));
                case BepcResultCommand bepc ->
                    bepcResults.add(BepcResult.from(session, bepc));
                case C1asResultCommand c1as ->
                    c1asResults.add(C1asResult.from(session, c1as));
            }
        }

        if (!bacResults.isEmpty()) {
            bacResultRepository.saveAll(bacResults);
        }
        if (!bepcResults.isEmpty()) {
            bepcResultRepository.saveAll(bepcResults);
        }
        if (!c1asResults.isEmpty()) {
            c1asResultRepository.saveAll(c1asResults);
        }
    }
}
