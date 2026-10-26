package mr.natijati.results.importing.async;

import java.io.IOException;
import java.nio.file.*;

/** Test/local adapter, deliberately not auto-configured in production. */
public final class FileSystemImportObjectStorage implements ImportObjectStorage {
    private final Path root;
    public FileSystemImportObjectStorage(Path root) { this.root = root.toAbsolutePath().normalize(); }
    private Path path(String key) { return root.resolve(ImportObjectStorage.validateKey(key)); }
    public void store(String key, Path source) throws IOException {
        Path target = path(key);
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
    }
    public void download(String key, Path destination) throws IOException { Files.copy(path(key), destination, StandardCopyOption.REPLACE_EXISTING); }
    public void delete(String key) throws IOException { Files.deleteIfExists(path(key)); }
    public boolean exists(String key) { return Files.exists(path(key)); }
}
