package mr.natijati.results.importing;

/** Technical diagnostic only: never includes a candidate name or birth date. */
public record ImportIssue(String examType, int rowNumber, String field, String value, String reason) { }
