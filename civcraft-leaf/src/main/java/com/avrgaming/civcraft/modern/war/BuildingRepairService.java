package com.avrgaming.civcraft.modern.war;
import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.build.*;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
public final class BuildingRepairService {
    private final CivCraftModernPlugin plugin;private final StorageBootstrap storage;
    private Long current;private List<BuildingRepairStore.Block> blocks;private int index;private boolean paused;private long lastWarning;
    public BuildingRepairService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;storage.transaction(db->{BuildingRepairStore.install(db);return null;});
    }
    private World world(String name){var w=Bukkit.getWorld(name);if(w==null)throw new IllegalStateException("Repair world unavailable: "+name);return w;}
    private String technology(String type) {
        return plugin.legacyStructures().listDefinitions(Integer.MAX_VALUE).stream().filter(d->d.id().equals(type)).findFirst().orElseThrow(()->new IllegalArgumentException("Не найдены настройки здания.")).requiredTechnology();
    }
    private void checkWar(Connection db,long now)throws SQLException {
        try{WarClockStore.requireDiplomacyOpen(db,plugin.warClock().calendar(),now);}catch(IllegalArgumentException failure){throw new IllegalArgumentException("Во время боевого периода ремонт запрещён.");}
    }
    public void command(Player player,boolean confirmed)throws SQLException,IOException {
        var loc=player.getLocation();
        long build=storage.query(db->{try(var s=db.prepareStatement("SELECT b.id FROM structure_builds b JOIN residents r ON r.town_id=b.town_id WHERE r.uuid=? AND b.world=? AND b.status NOT IN ('CANCELLED','DEMOLISHED') ORDER BY ((b.x-?)*(b.x-?)+(b.y-?)*(b.y-?)+(b.z-?)*(b.z-?)),b.id LIMIT 1")) {
            s.setString(1,player.getUniqueId().toString());s.setString(2,loc.getWorld().getName());s.setDouble(3,loc.getX());s.setDouble(4,loc.getX());s.setDouble(5,loc.getY());s.setDouble(6,loc.getY());s.setDouble(7,loc.getZ());s.setDouble(8,loc.getZ());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("В вашем городе нет ближайшего здания.");return r.getLong(1);}
        }});
        var offer=storage.query(db->{checkWar(db,System.currentTimeMillis());var o=BuildingRepairStore.offer(db,build,player.getUniqueId());BuildingRepairStore.validate(db,o,technology(o.type()),System.currentTimeMillis());return o;});
        if(!confirmed){player.sendMessage(Component.text("Ремонт "+offer.name()+" (#"+build+") стоит "+offer.cost()+" монет из казны города. Подтвердите: /build repairnearest yes"));return;}
        var plan=plan(offer);String tech=technology(offer.type());
        storage.transaction(db->{checkWar(db,System.currentTimeMillis());BuildingRepairStore.prepare(db,build,player.getUniqueId(),tech,plan,System.currentTimeMillis());return null;});
        player.sendMessage(Component.text("Ремонт здания #"+build+" начат. Списано "+offer.cost()+" монет из казны города."));
        interrupt("prepared",build);
    }
    private List<BuildingRepairStore.Block> plan(BuildingRepairStore.Offer offer)throws IOException,SQLException {
        Path path=Path.of(offer.template());if(!path.toString().toLowerCase(Locale.ROOT).endsWith(".def"))throw new IllegalArgumentException("Ремонт этого формата шаблона ещё не перенесён.");
        var tpl=new LegacyDefTemplateLoader().load(path);
        var defined=new HashMap<String,LegacyBlock>();for(var b:tpl.blocks())defined.put(b.x()+":"+b.y()+":"+b.z(),b);
        var result=new ArrayList<BuildingRepairStore.Block>();World w=world(offer.world());
        for(int y=0;y<tpl.sizeY();y++)for(int x=0;x<tpl.sizeX();x++)for(int z=0;z<tpl.sizeZ();z++) {
            int bx=offer.x()+x,by=offer.y()+y,bz=offer.z()+z;var block=w.getBlockAt(bx,by,bz);
            boolean foreign=storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM protected_blocks WHERE world=? AND x=? AND y=? AND z=? AND build_id<>?")){s.setString(1,offer.world());s.setInt(2,bx);s.setInt(3,by);s.setInt(4,bz);s.setLong(5,offer.build());try(var r=s.executeQuery()){return r.next();}}});
            if(foreign)throw new IllegalArgumentException("Шаблон ремонта пересекает другое здание.");
            // Keep original inventories, signs and block entity data intact.
            if(block.getState() instanceof TileState)continue;
            var b=defined.get(x+":"+y+":"+z);String data=b==null?"minecraft:air":LegacyBlockDataFactory.create(b).getAsString();
            result.add(new BuildingRepairStore.Block(offer.world(),bx,by,bz,data));
        }return List.copyOf(result);
    }
    private void interrupt(String phase,long build) {
        if(build==Long.getLong("civcraft.test.repair.build",-1) && phase.equals(System.getProperty("civcraft.test.repair.interruption"))){paused=true;throw new IllegalStateException("Injected repair interruption at "+phase);}
    }
    public void tick() {
        if(paused)return;
        try {
            if(current==null){current=storage.query(BuildingRepairStore::next);if(current==null)return;blocks=storage.query(db->BuildingRepairStore.blocks(db,current));index=0;}
            int budget=256;while(index<blocks.size() && budget-->0) {
                var b=blocks.get(index);var target=world(b.world()).getBlockAt(b.x(),b.y(),b.z());
                if(target.getState() instanceof TileState)throw new IllegalStateException("New block entity occupies a pending repair block");
                var data=Bukkit.createBlockData(b.data());if(!target.getBlockData().matches(data))target.setBlockData(data,false);index++;
            }
            if(index<blocks.size())return;
            var names=new HashSet<String>();for(var b:blocks)names.add(b.world());for(var name:names)world(name).save(true);
            long build=storage.query(db->{try(var s=db.prepareStatement("SELECT build_id FROM building_repair_jobs WHERE id=?")){s.setLong(1,current);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}});interrupt("world-saved",build);
            Long finished=storage.transaction(db->BuildingRepairStore.complete(db,current));current=null;blocks=null;index=0;
            if(finished!=null){plugin.governments().refreshQueuedProduction();plugin.getServer().broadcast(Component.text("Здание #"+finished+" отремонтировано."));}
        }catch(SQLException|RuntimeException failure) {
            if(!paused){current=null;blocks=null;index=0;}
            if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();plugin.getLogger().severe("Building repair failed; journal retained: "+failure.getMessage());}
        }
    }
}
