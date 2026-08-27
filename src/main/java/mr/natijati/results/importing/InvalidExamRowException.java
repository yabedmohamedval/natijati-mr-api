package mr.natijati.results.importing;

public class InvalidExamRowException extends RuntimeException {

    public InvalidExamRowException(int rowNumber, String message) {
        super("Ligne Excel " + rowNumber + " invalide : " + message);
    }

    public InvalidExamRowException(int rowNumber, String message, Throwable cause) {
        super("Ligne Excel " + rowNumber + " invalide : " + message, cause);
    }
}
