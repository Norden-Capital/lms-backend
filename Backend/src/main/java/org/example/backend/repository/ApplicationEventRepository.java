package org.example.backend.repository;

import org.example.backend.entity.ApplicationEvent;
import org.example.backend.entity.LoanApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationEventRepository extends JpaRepository<ApplicationEvent, Long> {

    List<ApplicationEvent> findByApplicationOrderByCreatedAtAscIdAsc(LoanApplication application);
}
