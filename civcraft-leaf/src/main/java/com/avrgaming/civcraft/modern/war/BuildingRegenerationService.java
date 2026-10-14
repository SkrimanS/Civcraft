package com.avrgaming.civcraft.modern.war;
import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;
public final class BuildingRegenerationService {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final Map<String,Integer> rates=new HashMap<>();
    private final int wonderRate;
    public BuildingRegenerationService(CivCraftModernPlugin plugin,StorageBootstrap storage) {
        this.plugin=plugin;this.storage=storage;
        var folder=plugin.getDataFolder().toPath().resolve("civcraft/data");
        for(String section:List.of("structures","wonders"))for(var raw:YamlConfiguration.loadConfiguration(folder.resolve(section+".yml").toFile()).getMapList(section)) {
            Object configured=raw.get("regen_rate");int rate=configured==null?0:Integer.parseInt(configured.toString());
            if(rate<0)throw new IllegalArgumentException("Negative regeneration rate: "+raw.get("id"));
            rates.put(String.valueOf(raw.get("id")),rate);
        }
        wonderRate=YamlConfiguration.loadConfiguration(folder.resolve("buffs.yml").toFile()).getMapList("buffs").stream().filter(raw->"buff_chichen_itza_regen_rate".equals(raw.get("id"))).mapToInt(raw->Integer.parseInt(raw.get("value").toString())).findFirst().orElseThrow(()->new IllegalArgumentException("Missing Chichen Itza regeneration buff"));
        if(wonderRate<0)throw new IllegalArgumentException("Negative Chichen Itza regeneration");
    }
    public void tick() {
        try{storage.transaction(db->BuildingRegenerationStore.tick(db,rates,wonderRate,(connection,id)->{var state=HallValidationStore.state(connection,id);return !state.validated()||state.valid();}));}
        catch(java.sql.SQLException failure){plugin.getLogger().severe("Building regeneration failed: "+failure.getMessage());}
    }
}
