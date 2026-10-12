package com.avrgaming.civcraft.modern.campnpc.gui;

import com.avrgaming.civcraft.modern.campnpc.bridge.CampRole;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.entity.Player;

public record GuiContext(Player player, long campId, String campName, CampRole role) {
    public Map<String, String> placeholders() {
        Map<String, String> map = new HashMap<>();
        map.put("player", player.getName());
        map.put("camp_id", String.valueOf(campId));
        map.put("camp_name", campName == null ? "" : campName);
        map.put("role", role.name());
        return map;
    }
}
