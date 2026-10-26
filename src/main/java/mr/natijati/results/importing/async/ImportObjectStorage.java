package mr.natijati.results.importing.async;

import java.io.IOException;
import java.nio.file.Path;

public interface ImportObjectStorage {
    void store(String key, Path source) throws IOException;
    void download(String key, Path destination) throws IOException;
    void delete(String key) throws IOException;
    boolean exists(String key) throws IOException;
    static String validateKey(String key) {
        if (key == null || !key.matches("imports/[0-9a-f-]{36}/source\\.xlsx?"))
            throw new IllegalArgumentException("Clé objet invalide");
        return key;
    }
}
