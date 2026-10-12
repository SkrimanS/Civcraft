package com.avrgaming.civcraft.modern.campnpc.util;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class Text {
    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    public static Component component(String text) {
        return AMP.deserialize(text == null ? "" : text);
    }

    public static String apply(String text, Map<String, String> placeholders) {
        String result = text == null ? "" : text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
