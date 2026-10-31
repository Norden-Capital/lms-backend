package org.example.backend.repository;

import org.example.backend.entity.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long> {

    Optional<Client> findByEmail(String email);

    /** Safe when the same person applies twice at the same moment: the second one simply adds nothing. */
    @Modifying
    @Query(value = """
            INSERT INTO clients (public_id, email) VALUES (:publicId, :email)
            ON CONFLICT (email) DO NOTHING""", nativeQuery = true)
    void addIfNew(@Param("publicId") String publicId, @Param("email") String email);
}
