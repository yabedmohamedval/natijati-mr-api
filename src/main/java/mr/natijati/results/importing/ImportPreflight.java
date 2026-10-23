package mr.natijati.results.importing;

import mr.natijati.results.exam.ExamType;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@Service
public class ImportPreflight {
    private final int maxRows;
    public ImportPreflight(@Value("${app.import-max-rows}") int maxRows) { this.maxRows = maxRows; }

    /** No database writes: reject the complete file before reserving a session. */
    public long validate(Path file, ExamType exam) { return inspect(file, exam).rows(); }

    public record Report(long rows, int ignoredColumns) { }

    public Report inspect(Path file, ExamType exam) {
        var reader = new ExcelResultItemReader(file, exam, maxRows);
        var processor = new ExamResultProcessor(exam);
        Set<Long> identifiers = new HashSet<>();
        long count = 0;
        try {
            reader.open(new ExecutionContext());
            RawExamRow row;
            while ((row = reader.read()) != null) {
                if (++count > maxRows) throw new InvalidExamRowException(exam, row.rowNumber(), "ROWS", "Limite de lignes dépassée : " + maxRows);
                ExamResultCommand command = processor.process(row);
                Long identifier = switch (command) {
                    case BacResultCommand bac -> bac.candidateNumber();
                    case BepcResultCommand bepc -> bepc.candidateNumber();
                    case C1asResultCommand c1as -> c1as.dossierNumber();
                };
                if (!identifiers.add(identifier)) throw new InvalidExamRowException(exam, row.rowNumber(), "IDENTIFIER", "Identifiant dupliqué dans le fichier");
            }
            if (count == 0) throw new InvalidExamRowException(exam, 0, "ROWS", "Aucune ligne de résultat");
            return new Report(count, reader.ignoredColumnCount());
        } finally { reader.close(); }
    }
}
