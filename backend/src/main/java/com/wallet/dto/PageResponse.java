package com.wallet.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * A small, stable pagination envelope for any paginated endpoint (first
 * used by GET /api/wallet/transactions - PROJECT_SPEC.md Section 5). See
 * RegisterResponse's javadoc for why this is a plain Java `record`.
 *
 * Why not just return Spring Data's own {@link Page} object directly?
 * Because its default JSON shape (a "pageable" sub-object, plus fields
 * Spring itself warns are unstable) is an internal implementation detail,
 * not a contract we control - Spring has literally logged deprecation
 * warnings about serializing it directly across versions. Wrapping the
 * results in our OWN tiny, explicit shape means the frontend depends on a
 * contract WE own and can keep stable, regardless of Spring version.
 *
 * The generic type {@code <T>} means this one class works for a page of
 * anything - a page of TransactionResponse now, a page of something else
 * later - without writing a new wrapper each time.
 */
public record PageResponse<T>(

        /** The items on THIS page (already mapped to DTOs), e.g. up to 20 transactions. */
        List<T> content,

        /** Zero-based index of the current page (page 0 is the first page). */
        int page,

        /** How many items were requested per page. */
        int size,

        /** Total number of items across ALL pages (lets the UI show "1-20 of 137" and compute page controls). */
        long totalElements,

        /** Total number of pages available given totalElements and size. */
        int totalPages,

        /** True if there is no page after this one - convenient for disabling a "Next" button. */
        boolean last
) {

    /**
     * Convert a Spring Data {@link Page} of ENTITIES into a PageResponse of
     * DTOs, applying {@code mapper} to each item. This keeps the two
     * concerns separate: the repository/service deals in Page<Entity>, and
     * this factory turns it into the Page<Dto> envelope the API returns.
     *
     * Example: {@code PageResponse.from(txnPage, t -> TransactionResponse.from(t, walletId))}.
     */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        List<T> mappedContent = page.getContent().stream()
                .map(mapper)
                .toList();

        return new PageResponse<>(
                mappedContent,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast()
        );
    }
}
