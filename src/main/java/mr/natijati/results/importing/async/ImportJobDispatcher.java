package mr.natijati.results.importing.async;

import java.io.IOException;
import java.util.UUID;

public interface ImportJobDispatcher {
    /** Returns the long-running operation name, NOT the execution name. Never waits for completion. */
    String dispatch(UUID importId) throws IOException;
}
