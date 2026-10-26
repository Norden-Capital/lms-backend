package org.example.backend.service;

import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import org.example.backend.dto.ActionLogResponse;
import org.example.backend.dto.PageResponse;
import org.example.backend.entity.ActionLog;
import org.example.backend.entity.ActionType;
import org.example.backend.entity.User;
import org.example.backend.exception.ApiException;
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
import java.util.Map;

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

    /** What the page's columns sort by, and the field behind each. */
    private static final Map<String, String> SORTABLE = Map.of(
            "at", "createdAt",
            "action", "action",
            "actor", "actorLabel",
            "target", "targetLabel",
            "details", "details",
            "ip", "ip"
    );

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
    public PageResponse<ActionLogResponse> list(ActionType action, String search, int page, int size, String sort) {
        return list(action, search, null, page, size, sort);
    }

    /**
     * {@code user} is a person's public id (NC-…): only lines where they did
     * something or had something done to them — their Activity on Users.
     */
    @Transactional(readOnly = true)
    public PageResponse<ActionLogResponse> list(ActionType action, String search, String user,
                                                int page, int size, String sort) {
        Specification<ActionLog> spec = (root, query, builder) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (action != null) {
                conditions.add(builder.equal(root.get("action"), action));
            }
            if (user != null && !user.isBlank()) {
                // Left joins: a line with no actor (a failed sign-in) is still about its target.
                var actor = root.join("actor", jakarta.persistence.criteria.JoinType.LEFT);
                var target = root.join("target", jakarta.persistence.criteria.JoinType.LEFT);
                // Ids are stored in capitals; nc-ab12-cd34 finds the same person,
                // as it does on the user's own page — added 2026-10-26.
                String id = user.strip().toUpperCase(java.util.Locale.ROOT);
                conditions.add(builder.or(
                        builder.equal(actor.get("publicId"), id),
                        builder.equal(target.get("publicId"), id)));
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
                parseSort(sort).and(Sort.by(Sort.Direction.DESC, "id"))));

        return PageResponse.from(found.map(ActionLogResponse::from));
    }

    /** `field,direction`, refusing anything unlisted; newest first when absent. */
    private static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        String[] parts = sort.split(",");
        String property = SORTABLE.get(parts[0].trim());
        if (property == null) {
            throw ApiException.badRequest(
                    "Cannot sort by " + parts[0].trim() + ". Allowed: " + String.join(", ", SORTABLE.keySet()) + ".");
        }
        Sort.Direction direction = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc")
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        return Sort.by(direction, property);
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
