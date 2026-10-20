package org.example.backend.service;

import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import org.example.backend.dto.ActionLogResponse;
import org.example.backend.dto.PageResponse;
import org.example.backend.entity.ActionLog;
import org.example.backend.entity.ActionType;
import org.example.backend.entity.User;
import org.example.backend.repository.ActionLogRepository;
import org.example.backend.repository.UserRepository;
import org.example.backend.security.ClientIp;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes and reads the action log — who did what to whom, and when. Only the
 * Owner can read it.
 *
 * <p>A line joins the transaction of the action it describes: if the action is
 * rolled back, so is its line, and the log never claims something happened
 * that did not.
 */
@Service
public class ActionLogService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ActionLogRepository logRepository;
    private final UserRepository userRepository;
    private final ClientIp clientIp;

    public ActionLogService(ActionLogRepository logRepository,
                            UserRepository userRepository,
                            ClientIp clientIp) {
        this.logRepository = logRepository;
        this.userRepository = userRepository;
        this.clientIp = clientIp;
    }

    /** Somebody signed in did something. */
    @Transactional
    public void record(ActionType action, Long actorId, User target, String details) {
        User actor = actorId == null ? null : userRepository.findById(actorId).orElse(null);
        record(action, actor, target, details);
    }

    @Transactional
    public void record(ActionType action, User actor, User target, String details) {
        logRepository.save(ActionLog.builder()
                .action(action)
                .actor(actor)
                .actorLabel(label(actor))
                .target(target)
                .targetLabel(label(target))
                .details(details == null ? null : truncate(details, 500))
                .ip(currentIp())
                .build());
    }

    /** One page, newest first, filtered by kind and by any text in the line. */
    @Transactional(readOnly = true)
    public PageResponse<ActionLogResponse> list(ActionType action, String search, int page, int size) {
        Specification<ActionLog> spec = (root, query, builder) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (action != null) {
                conditions.add(builder.equal(root.get("action"), action));
            }
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                conditions.add(builder.or(
                        builder.like(builder.lower(root.get("actorLabel")), pattern),
                        builder.like(builder.lower(root.get("targetLabel")), pattern),
                        builder.like(builder.lower(root.get("details")), pattern),
                        builder.like(builder.lower(root.get("ip")), pattern)
                ));
            }
            return builder.and(conditions.toArray(Predicate[]::new));
        };

        var found = logRepository.findAll(spec, PageRequest.of(
                Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))));

        return PageResponse.from(found.map(ActionLogResponse::from));
    }

    /** "Sarah Amrani <sarah@…>", as it reads at the moment of the action. */
    private static String label(User user) {
        return user == null ? null : truncate(user.fullName() + " <" + user.getEmail() + ">", 400);
    }

    private String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return truncate(clientIp.resolve(request), 64);
        }
        return null;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
