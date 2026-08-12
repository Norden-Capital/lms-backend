package org.example.backend.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * One page of results, and enough to draw the page numbers under a table.
 *
 * <p>Spring's own {@code Page} is deliberately not returned directly. Its JSON
 * shape is an implementation detail of Spring Data — it has changed between
 * versions and carries a dozen fields nobody uses — so pinning our own record
 * here means the frontend contract does not move when the framework does.
 *
 * @param content       the rows themselves
 * @param page          zero-based, as the caller asked for
 * @param size          how many were asked for, not how many came back
 * @param totalElements every row that matched the filters, not just this page —
 *                      this is the number the pagination is drawn from
 * @param totalPages    derived, but sent so the frontend does not repeat the
 *                      division and round it differently
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
