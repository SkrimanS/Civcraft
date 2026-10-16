package com.avrgaming.civcraft.modern.wonder;
import java.sql.*;
import java.util.UUID;
import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import org.bukkit.attribute.Attribute;

/** The legacy one-second wonder update heals civilization members on the host town's claims. */
public final class HangingGardensHealing {
    private final CivCraftModernPlugin plugin;private final StorageBootstrap storage;
    public HangingGardensHealing(CivCraftModernPlugin plugin,StorageBootstrap storage){this.plugin=plugin;this.storage=storage;}
    public static boolean eligible(Connection db,UUID player,String world,int chunkX,int chunkZ)throws SQLException {
        try(var s=db.prepareStatement("""
            SELECT 1 FROM residents r JOIN towns home ON home.id=r.town_id AND home.civ_id=r.civ_id
            JOIN town_claims claim ON claim.world=? AND claim.chunk_x=? AND claim.chunk_z=?
            JOIN towns host ON host.id=claim.town_id AND host.civ_id=home.civ_id
            JOIN active_structure_builds wonder ON wonder.town_id=host.id
            WHERE r.uuid=? AND wonder.structure_id='w_hanginggardens' AND wonder.status IN ('QUEUED','BUILDING','COMPLETE') LIMIT 1
            """)) {
            s.setString(1,world);s.setInt(2,chunkX);s.setInt(3,chunkZ);s.setString(4,player.toString());try(var r=s.executeQuery()){return r.next();}
        }
    }
    public static double nextHealth(double health,double maximum) {
        if(!Double.isFinite(health)||!Double.isFinite(maximum)||health<0||maximum<=0||health>maximum)throw new IllegalArgumentException("Invalid player health");
        if(health==0||health>=20||health>=maximum)return health;
        return Math.min(Math.min(20,maximum),health+1);
    }
    public void tick() {
        try {
            for(var player:plugin.getServer().getOnlinePlayers()) {
                if(player.isDead()||!player.isValid()||player.getHealth()>=20)continue;
                var location=player.getLocation();boolean allowed=storage.query(db->eligible(db,player.getUniqueId(),location.getWorld().getName(),Math.floorDiv(location.getBlockX(),16),Math.floorDiv(location.getBlockZ(),16)));
                if(allowed){var attribute=player.getAttribute(Attribute.MAX_HEALTH);double next=nextHealth(player.getHealth(),attribute==null?20:attribute.getValue());if(next!=player.getHealth())player.setHealth(next);}
            }
        }catch(SQLException failure){plugin.getLogger().warning("Hanging Gardens healing failed: "+failure.getMessage());}
    }
}
