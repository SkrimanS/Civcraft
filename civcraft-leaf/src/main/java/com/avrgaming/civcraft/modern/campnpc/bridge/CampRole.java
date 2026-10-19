package com.avrgaming.civcraft.modern.campnpc.bridge;

public enum CampRole {
    LEADER,
    ELDER,
    MEMBER,
    NONE;

    public boolean isMember() {
        return this == LEADER || this == ELDER || this == MEMBER;
    }
    public String displayName(){return switch(this){case LEADER->"Вождь";case ELDER->"Старейшина";case MEMBER->"Участник";case NONE->"Не состоит в лагере";};}

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
