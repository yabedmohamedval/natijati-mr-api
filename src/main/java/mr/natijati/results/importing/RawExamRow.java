package mr.natijati.results.importing;

import java.util.Map;

public record RawExamRow(
    int rowNumber,
    Map<String, String> values
) {
}
