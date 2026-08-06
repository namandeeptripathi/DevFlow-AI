package com.devflow.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Data Transfer Object wrapping a paged user search result set.
 *
 * @see UserSearchResult
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class UserSearchResponse {

    /** List of search result items for the current page. */
    private List<UserSearchResult> content;

    /** Current zero-indexed page number. */
    private int pageNumber;

    /** Number of elements per page requested. */
    private int pageSize;

    /** Total count of elements matching the search query across all pages. */
    private long totalElements;

    /** Total number of available pages. */
    private int totalPages;

    /** Whether the current page is the first page. */
    private boolean isFirst;

    /** Whether the current page is the last page. */
    private boolean isLast;

    /** Whether another page exists after the current page. */
    private boolean hasNext;

    /** Whether a page exists prior to the current page. */
    private boolean hasPrevious;

    /**
     * Constructs a {@link UserSearchResponse} wrapper from a Spring Data {@link Page}.
     *
     * @param page Spring Data page containing search result items
     * @return the populated response DTO
     */
    public static UserSearchResponse fromPage(Page<UserSearchResult> page) {
        return UserSearchResponse.builder()
                .content(page.getContent())
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .isFirst(page.isFirst())
                .isLast(page.isLast())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .build();
    }
}
