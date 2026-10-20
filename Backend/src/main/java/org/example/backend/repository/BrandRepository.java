package org.example.backend.repository;

import org.example.backend.entity.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BrandRepository extends JpaRepository<Brand, Long> {

    Optional<Brand> findByPublicId(String publicId);

    Optional<Brand> findByEmailIgnoreCase(String email);

    Optional<Brand> findByDomain(String domain);

    List<Brand> findAllByOrderByNameAscEmailAsc();

    /** The brands the inbox uses — switched-off ones are left out. */
    List<Brand> findAllByActiveTrueOrderByNameAscEmailAsc();
}
