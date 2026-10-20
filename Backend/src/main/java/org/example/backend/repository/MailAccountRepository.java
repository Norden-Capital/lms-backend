package org.example.backend.repository;

import org.example.backend.entity.MailAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MailAccountRepository extends JpaRepository<MailAccount, Long> {

    Optional<MailAccount> findByPublicId(String publicId);

    boolean existsByEmailIgnoreCase(String email);

    List<MailAccount> findAllByOrderByBrandAscLabelAsc();
}
