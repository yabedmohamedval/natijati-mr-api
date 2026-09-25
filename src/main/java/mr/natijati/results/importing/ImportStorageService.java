package mr.natijati.results.importing;

import org.apache.poi.poifs.filesystem.FileMagic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.zip.ZipFile;

@Service
public class ImportStorageService {
    private final Path importDirectory;
    private static final long MAX_BYTES = 50L * 1024 * 1024;

    public ImportStorageService(@Value("${app.import-directory}") String directory) {
        importDirectory = Path.of(directory).toAbsolutePath().normalize();
    }

    public Path store(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (file.isEmpty()) throw new IllegalArgumentException("Le fichier est vide");
        if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException("Fichier supérieur à 50 MiB");
        if (!name.endsWith(".xls") && !name.endsWith(".xlsx")) throw new IllegalArgumentException("Extension Excel .xls ou .xlsx requise");
        Files.createDirectories(importDirectory);
        Path path = Files.createTempFile(importDirectory, "import-", name.endsWith(".xlsx") ? ".xlsx" : ".xls");
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            try (var in = file.getInputStream(); var out = Files.newOutputStream(path)) {
                long copied = 0;
                byte[] buffer = new byte[8192];
                int length;
                while ((length = in.read(buffer)) != -1) {
                    copied += length;
                    if (copied > MAX_BYTES) throw new IllegalArgumentException("Fichier supérieur à 50 MiB");
                    out.write(buffer, 0, length);
                }
            }
            FileMagic magic = FileMagic.valueOf(path.toFile());
            if (magic != (name.endsWith(".xlsx") ? FileMagic.OOXML : FileMagic.OLE2))
                throw new IllegalArgumentException("Format réel incompatible avec l'extension Excel");
            if (magic == FileMagic.OOXML) validateZip(path);
            return path;
        } catch (IOException | RuntimeException ex) {
            delete(path);
            throw ex;
        }
    }

    private void validateZip(Path path) throws IOException {
        // Complement POI's built-in zip-bomb protection; never relax its compression ratio.
        try (var zip = new ZipFile(path.toFile())) {
            long expanded = 0;
            var entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (++count > 10000 || entry.getSize() < 0 || entry.getSize() > 100L * 1024 * 1024)
                    throw new IllegalArgumentException("Archive Excel hors limites");
                expanded += entry.getSize();
                if (expanded > 256L * 1024 * 1024) throw new IllegalArgumentException("Archive Excel décompressée trop volumineuse");
            }
        } catch (java.util.zip.ZipException ex) {
            throw new IllegalArgumentException("Archive Excel corrompue");
        }
    }

    public void delete(Path path) {
        if (path == null) return;
        if (!path.toAbsolutePath().normalize().getParent().equals(importDirectory))
            throw new IllegalArgumentException("Nettoyage hors du répertoire d'import interdit");
        try { Files.deleteIfExists(path); }
        catch (IOException ex) { LoggerFactory.getLogger(getClass()).error("import_file_cleanup_failed file={} type={}", path.getFileName(), ex.getClass().getSimpleName()); }
    }
}
