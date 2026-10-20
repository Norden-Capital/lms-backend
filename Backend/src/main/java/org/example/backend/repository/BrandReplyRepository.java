package org.example.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * The replies sent from each brand — see migration V14. Plain SQL: a row is
 * written once and only ever counted, so it needs no entity of its own.
 */
@Repository
public interface BrandReplyRepository extends JpaRepository<org.example.backend.entity.Brand, Long> {

    @Modifying
    @Query(value = "insert into brand_replies (brand_id) values (:brandId)", nativeQuery = true)
    void record(Long brandId);

    /** [brand id, quarter 1–4, count] for replies sent from {@code from} on. */
    @Query(value = """
            select brand_id, cast(extract(quarter from sent_at at time zone 'UTC') as int), count(*)
              from brand_replies
             where sent_at >= :from
             group by 1, 2""", nativeQuery = true)
    List<Object[]> countByQuarterSince(Instant from);
}
