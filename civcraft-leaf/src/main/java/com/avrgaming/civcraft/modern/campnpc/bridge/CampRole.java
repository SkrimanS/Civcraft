package com.avrgaming.civcraft.modern.campnpc.bridge;

public enum CampRole {
    LEADER,
    ELDER,
    MEMBER,
    NONE;

    public boolean isMember() {
        return this == LEADER || this == ELDER || this == MEMBER;
    }

    public static CampRole fromObject(Object value) {
        if (value == null) {
            return NONE;
        }
        try {
            return CampRole.valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return NONE;
        }
    }
}
