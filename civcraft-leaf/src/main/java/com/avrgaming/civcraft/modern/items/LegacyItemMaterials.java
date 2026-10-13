package com.avrgaming.civcraft.modern.items;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.material.MaterialData;

/** Numeric IDs are read from the original 1.11 registry, never guessed as modern IDs. */
@SuppressWarnings("deprecation")
public final class LegacyItemMaterials {
    private final Properties names = new Properties();
    public LegacyItemMaterials(InputStream input) throws IOException {
        if (input == null) throw new IOException("Missing original material registry");
        try (input) { names.load(input); }
    }
    public Material resolve(int id, int data) {
        String name = names.getProperty(Integer.toString(id));
        if (name == null) throw new IllegalArgumentException("Неизвестный старый ID материала: " + id);
        Material legacy = Material.matchMaterial("LEGACY_" + name);
        if (legacy == null) throw new IllegalArgumentException("Нет legacy-материала: " + name);
        Material material = Bukkit.getUnsafe().fromLegacy(new MaterialData(legacy, (byte)Math.max(0,data)), true);
        if (material == null || material.isAir()) throw new IllegalArgumentException("Нельзя преобразовать предмет " + id + ":" + data);
        return material;
    }
    public List<Material> choices(int id, int data) {
        if (data >= 0) return List.of(resolve(id,data));
        List<Material> result = new ArrayList<>();
        for (int variant=0;variant<16;variant++) {
            try { Material material=resolve(id,variant); if(!result.contains(material))result.add(material); }
            catch (IllegalArgumentException ignored) { }
        }
        if (result.isEmpty())throw new IllegalArgumentException("Нет вариантов материала " + id);
        return List.copyOf(result);
    }
}
