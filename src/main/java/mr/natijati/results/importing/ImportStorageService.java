package mr.natijati.results.importing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;
import java.util.UUID;

@Service
public class ImportStorageService {

    private final Path importDirectory;

    public ImportStorageService(
        @Value("${app.import-directory}") String importDirectory
    ) {
        this.importDirectory = Path.of(importDirectory)
            .toAbsolutePath()
            .normalize();
    }

    public Path store(MultipartFile file) throws IOException {
        validate(file);
        Files.createDirectories(importDirectory);

        String original = file.getOriginalFilename() == null
            ? "results.xlsx"
            : Path.of(file.getOriginalFilename()).getFileName().toString();

        String storedName = UUID.randomUUID() + "-" + original;
        Path destination = importDirectory.resolve(storedName).normalize();

        if (!destination.startsWith(importDirectory)) {
            throw new IllegalArgumentException("Nom de fichier invalide");
        }

        try (var inputStream = file.getInputStream()) {
            Files.copy(inputStream, destination, StandardCopyOption.REPLACE_EXISTING);
        }

        return destination;
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Le fichier est vide");
        }

        String filename = file.getOriginalFilename() == null
            ? ""
            : file.getOriginalFilename().toLowerCase(Locale.ROOT);

        if (!filename.endsWith(".xlsx") && !filename.endsWith(".xls")) {
            throw new IllegalArgumentException(
                "Seuls les fichiers .xlsx et .xls sont acceptés"
            );
        }
    }
}
