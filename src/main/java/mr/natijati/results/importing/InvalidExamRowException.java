package mr.natijati.results.importing;

import mr.natijati.results.exam.ExamType;

public class InvalidExamRowException extends RuntimeException {
    private final ImportIssue issue;

    public InvalidExamRowException(ExamType exam, int row, String field, String reason) {
        super("Import " + exam + ", ligne " + row + ", champ " + field + " : " + reason);
        this.issue = new ImportIssue(exam.name(), row, field, "[masquée]", reason);
    }

    public ImportIssue getIssue() { return issue; }
}
