package org.example.backend.dto;

/**
 * ⚠️ A stand-in. There is no customer table and no customer entity.
 *
 * <p>This exists so the Customers module has something behind it while the
 * permission model is being proved end to end — a permission that guards nothing
 * cannot be tested, and one tested only in the frontend is not tested at all.
 *
 * <p>Delete this and its controller once real customers exist.
 */
public record CustomerResponse(Long id, String name, String channel, String status) {
}
