package com.avrgaming.civcraft.modern.api;

public enum CampRole {
    LEADER,
    ELDER,
    MEMBER,
    NONE;

    public static CampRole fromStorage(String role) {
        if (role == null || role.isBlank()) {
            return NONE;
        }
        return switch (role.trim().toUpperCase()) {
            case "LEADER" -> LEADER;
            case "ELDER" -> ELDER;
            case "MEMBER" -> MEMBER;
            default -> NONE;
        };
    }
}
