package com.avrgaming.civcraft.modern.culture;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.File;
import java.sql.SQLException;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public final class BiomeSurveyService {
    private final StorageBootstrap storage;
    private final BiomePotential catalog;
    public BiomeSurveyService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.storage=storage;
        catalog=new BiomePotential(YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/culture.yml")).getMapList("culture_biomes"));
        storage.query(db->{try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS culture_biome_cache(world TEXT NOT NULL,chunk_x INTEGER NOT NULL,chunk_z INTEGER NOT NULL,biome TEXT NOT NULL,sampled_y INTEGER NOT NULL,PRIMARY KEY(world,chunk_x,chunk_z))");}return null;});
    }
    public record Sample(String biome,int y,BiomePotential.Yield yield) {}
    public Sample sample(Location location)throws SQLException {
        int cx=Math.floorDiv(location.getBlockX(),16),cz=Math.floorDiv(location.getBlockZ(),16);
        String world=location.getWorld().getName();
        Sample cached=storage.query(db->{try(var s=db.prepareStatement("SELECT biome,sampled_y FROM culture_biome_cache WHERE world=? AND chunk_x=? AND chunk_z=?")) {
            s.setString(1,world);s.setInt(2,cx);s.setInt(3,cz);try(var r=s.executeQuery()){if(!r.next())return null;String biome=r.getString(1);return new Sample(biome,r.getInt(2),catalog.yield(biome));}
        }});
        if(cached!=null)return cached;
        int x=cx*16,z=cz*16,y=Math.max(location.getWorld().getMinHeight(),Math.min(location.getWorld().getMaxHeight()-1,location.getWorld().getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES)));
        String biome=location.getWorld().getBiome(x,y,z).getKey().getKey();
        storage.query(db->{try(var s=db.prepareStatement("INSERT OR IGNORE INTO culture_biome_cache VALUES(?,?,?,?,?)")){s.setString(1,world);s.setInt(2,cx);s.setInt(3,cz);s.setString(4,biome);s.setInt(5,y);s.executeUpdate();}return null;});
        return new Sample(biome,y,catalog.yield(biome));
    }
    public void show(Player player)throws SQLException {
        var location=player.getLocation();var sample=sample(location);var yield=sample.yield();
        player.sendMessage(Component.text("Потенциал чанка "+Math.floorDiv(location.getBlockX(),16)+", "+Math.floorDiv(location.getBlockZ(),16)+". Биом: ").append(Component.translatable("biome.minecraft."+sample.biome())).append(Component.text(".")));
        player.sendMessage(Component.text(String.format(Locale.ROOT,"Монеты: %.2f; молотки: %.2f; рост: %.2f; счастье: %.3f; колбы: %.2f.",yield.coins(),yield.hammers(),yield.growth(),yield.happiness(),yield.beakers())));
    }
}
