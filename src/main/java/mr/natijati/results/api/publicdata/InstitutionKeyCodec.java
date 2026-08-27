package mr.natijati.results.api.publicdata;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class InstitutionKeyCodec {

    public String encode(String examType, Integer year, String institutionName) {
        String raw = examType + "|" + year + "|" + institutionName;
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public DecodedInstitutionKey decode(String encoded) {
        try {
            String raw = new String(
                Base64.getUrlDecoder().decode(encoded),
                StandardCharsets.UTF_8
            );
            String[] parts = raw.split("\\|", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("Identifiant établissement invalide");
            }
            return new DecodedInstitutionKey(parts[0], Integer.valueOf(parts[1]), parts[2]);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Identifiant établissement invalide", ex);
        }
    }

    public record DecodedInstitutionKey(
        String examType,
        Integer year,
        String institutionName
    ) {
    }
}
