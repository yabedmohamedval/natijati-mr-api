package mr.natijati.results.api.publicdata.dto;

public record SearchCriteria(
    String examType,
    Integer year,
    String candidateNumber,
    String name,
    String wilaya,
    String series,
    String institution,
    Integer page,
    Integer size
) {
    public SearchCriteria {
        page = page == null || page < 0 ? 0 : page;
        size = size == null ? 20 : Math.min(Math.max(size, 1), 50);
    }
}
