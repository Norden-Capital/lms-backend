package org.example.backend.repository;

import org.example.backend.entity.Module;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModuleRepository extends JpaRepository<Module, String> {

    /** In menu order, which is the only order this is ever read in. */
    List<Module> findAllByOrderByPositionAsc();

    /** The modules a non-administrator may never hold, whatever is ticked for them. */
    List<Module> findByAdminOnlyTrue();
}
