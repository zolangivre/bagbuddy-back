package com.bagbuddy.tripservice.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Pageable qui respecte un offset quelconque. PageRequest ne connait que des numeros de page :
 * offset=30 limit=20 y devenait la page 1, soit les elements 20 a 39 -- dix lignes deja servies
 * et dix jamais vues. Hibernate ne lit que getOffset() et getPageSize(), on les porte tels quels.
 */
public record OffsetPageRequest(long offset, int limit, Sort sort) implements Pageable {

    public OffsetPageRequest {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        sort = sort == null ? Sort.unsorted() : sort;
    }

    public OffsetPageRequest(long offset, int limit) {
        this(offset, limit, Sort.unsorted());
    }

    @Override
    public int getPageNumber() {
        return (int) (offset / limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return new OffsetPageRequest(offset + limit, limit, sort);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetPageRequest(Math.max(offset - limit, 0), limit, sort) : first();
    }

    @Override
    public Pageable first() {
        return new OffsetPageRequest(0, limit, sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetPageRequest((long) pageNumber * limit, limit, sort);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
