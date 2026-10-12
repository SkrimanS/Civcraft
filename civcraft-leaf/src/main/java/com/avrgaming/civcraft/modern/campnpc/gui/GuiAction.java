package com.avrgaming.civcraft.modern.campnpc.gui;

public record GuiAction(String type, String value) {
    public static GuiAction parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new GuiAction("NONE", "");
        }
        String[] parts = raw.split(":", 2);
        return new GuiAction(parts[0].trim().toUpperCase(), parts.length > 1 ? parts[1].trim() : "");
    }
}
