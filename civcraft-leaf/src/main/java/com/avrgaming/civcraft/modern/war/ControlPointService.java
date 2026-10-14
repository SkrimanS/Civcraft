package com.avrgaming.civcraft.modern.war;
import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.build.LegacyDefTemplateLoader;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.configuration.file.YamlConfiguration;
public final class ControlPointService {
    private final CivCraftModernPlugin plugin;private final StorageBootstrap storage;
    private final int capitolHp,hallHp;private final Set<Long> loaded=new HashSet<>();private long lastWarning;
    public ControlPointService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;
        var config=YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("civcraft/data/war.yml").toFile());
        capitolHp=config.getInt("war.control_block_hitpoints_capitol",100);hallHp=config.getInt("war.control_block_hitpoints_townhall",20);
        if(capitolHp<1 || hallHp<1)throw new IllegalArgumentException("Control point health must be positive");
        storage.transaction(db->{ControlPointStore.install(db);return null;});
    }
    private void discover()throws Exception {
        record Hall(long id,String type,String path,String world,int x,int y,int z) {}
        var halls=storage.query(db->{var out=new ArrayList<Hall>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id,structure_id,template_path,world,x,y,z FROM structure_builds WHERE status='COMPLETE' AND structure_id IN ('s_townhall','s_capitol')")){while(r.next())out.add(new Hall(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5),r.getInt(6),r.getInt(7)));}return out;});
        for(var h:halls) {
            if(loaded.contains(h.id()))continue;
            if(!h.path().toLowerCase(Locale.ROOT).endsWith(".def"))throw new IllegalArgumentException("Control markers in schematic templates are not ported yet");
            var template=new LegacyDefTemplateLoader().load(Path.of(h.path()));var points=new ArrayList<ControlPointStore.Point>();
            for(var b:template.blocks())if(!b.signLines().isEmpty() && b.signLines().getFirst().trim().equalsIgnoreCase("/control"))
                points.add(new ControlPointStore.Point(h.id(),h.world(),h.x()+b.x(),h.y()+b.y()+1,h.z()+b.z(),h.type().equals("s_capitol")?capitolHp:hallHp,h.type().equals("s_capitol")?"SANDSTONE":"OAK_FENCE"));
            if(points.isEmpty())throw new IllegalArgumentException("Town hall template has no control markers");
            var w=Bukkit.getWorld(h.world());if(w==null)throw new IllegalStateException("Control point world unavailable");
            for(var p:points) {
                var base=w.getBlockAt(p.x(),p.y()-1,p.z());var top=w.getBlockAt(p.x(),p.y(),p.z());
                if((base.getState() instanceof TileState && !(base.getState() instanceof Sign)) || top.getState() instanceof TileState)throw new IllegalStateException("Control point would replace a block entity");
            }
            storage.transaction(db->{for(var p:points)ControlPointStore.register(db,p);return null;});loaded.add(h.id());
        }
    }
    public void tick() {
        try {
            discover();
            record Visible(ControlPointStore.Point point,int hp) {}
            var points=storage.query(db->{var out=new ArrayList<Visible>();long now=System.currentTimeMillis();
                Long war=null;try(var s=db.prepareStatement("SELECT start_at FROM war_sessions WHERE state='ACTIVE' AND start_at<=? AND end_at>? LIMIT 1")){s.setLong(1,now);s.setLong(2,now);try(var r=s.executeQuery()){if(r.next())war=r.getLong(1);}}
                try(var s=db.createStatement();var r=s.executeQuery("SELECT p.* FROM war_control_points p JOIN structure_builds b ON b.id=p.build_id WHERE b.status='COMPLETE'")){while(r.next()){var p=new ControlPointStore.Point(r.getLong("build_id"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getInt("maximum"),r.getString("base"));out.add(new Visible(p,war==null?p.maximum():ControlPointStore.health(db,p,war)));}}return out;});
            for(var v:points) {
                var p=v.point();var w=Bukkit.getWorld(p.world());if(w==null)continue;
                var base=w.getBlockAt(p.x(),p.y()-1,p.z());var top=w.getBlockAt(p.x(),p.y(),p.z());
                if((base.getState() instanceof TileState && !(base.getState() instanceof Sign)) || top.getState() instanceof TileState)throw new IllegalStateException("Control reconciliation would replace a block entity");
                var material=Material.valueOf(p.base());if(base.getType()!=material)base.setType(material,false);
                var expected=v.hp()>0?Material.OBSIDIAN:Material.AIR;if(top.getType()!=expected)top.setType(expected,false);
            }
        }catch(Exception failure){if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();plugin.getLogger().severe("Control point synchronization failed: "+failure.getMessage());}}
    }
}
