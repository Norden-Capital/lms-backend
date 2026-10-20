package org.example.backend.dto;

import org.example.backend.entity.ActionLog;
import org.example.backend.entity.User;

import java.time.Instant;

/**
 * One line of the action log, as the Owner's page shows it. People appear as
 * the text frozen at the time, plus their public ID (never the database's
 * number) when the account still exists — so the page can link to it.
 */
public record ActionLogResponse(
        Long id,
        Instant at,
        String action,
        Party actor,
        Party target,
        String details,
        String ip
) {
    /** Somebody in a log line: how they were named then, and where they are now. */
    public record Party(String label, String publicId) {
    }

    public static ActionLogResponse from(ActionLog log) {
        return new ActionLogResponse(
                log.getId(),
                log.getCreatedAt(),
                log.getAction().name(),
                party(log.getActorLabel(), log.getActor()),
                party(log.getTargetLabel(), log.getTarget()),
                log.getDetails(),
                log.getIp());
    }

    private static Party party(String label, User user) {
        if (label == null && user == null) return null;
        return new Party(label, user == null ? null : user.getPublicId());
    }
}
