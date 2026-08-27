package mr.natijati.results.importing;

public sealed interface ExamResultCommand
    permits BacResultCommand, BepcResultCommand, C1asResultCommand {
}
