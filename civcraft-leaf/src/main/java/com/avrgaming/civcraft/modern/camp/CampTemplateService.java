package com.avrgaming.civcraft.modern.camp;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.build.*;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.town.ConsumptionContainers.Position;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;

/** Exact source-template cells, shared by the garden and campfire implementations. */
public final class CampTemplateService implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final String structureId;
    private final Map<String,List<LegacyBlock>> parsed=new HashMap<>();
    private final Map<Position,Long> gardens=new HashMap<>();
    private final Set<Position> enabledGardens=new HashSet<>();
    private final Map<Long,Integer> initialized=new HashMap<>();
    private final Set<Long> indexed=new HashSet<>();
    private long lastWarning;
    public CampTemplateService(CivCraftModernPlugin plugin,String structureId)throws SQLException {
        this.plugin=plugin;this.storage=plugin.storage();this.structureId=structureId;
        storage.transaction(db->{try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_template_index(camp_id INTEGER PRIMARY KEY REFERENCES camps(id) ON DELETE CASCADE,build_id INTEGER NOT NULL REFERENCES structure_builds(id) ON DELETE CASCADE,path TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS camp_template_markers(camp_id INTEGER NOT NULL REFERENCES camp_template_index(camp_id) ON DELETE CASCADE,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,command TEXT NOT NULL,legacy_data INTEGER NOT NULL,parameter TEXT NOT NULL,PRIMARY KEY(world,x,y,z))");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS camp_template_marker_camp ON camp_template_markers(camp_id,command)");
        }return null;});
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::refresh,1L,20L);
    }
    public void refresh() {
        try {
            indexMissing();
            var cells=storage.query(db->{var out=new ArrayList<Cell>();try(var s=db.createStatement();var r=s.executeQuery("SELECT m.*,c.farm_upgrade,c.longhouse_upgrade,c.breaker_upgrade FROM camp_template_markers m JOIN camps c ON c.id=m.camp_id JOIN camp_template_index i ON i.camp_id=c.id JOIN structure_builds b ON b.id=i.build_id WHERE b.status='COMPLETE'")) {
                while(r.next())out.add(new Cell(r.getLong("camp_id"),new Position(r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z")),r.getString("command"),r.getInt("legacy_data"),r.getInt("farm_upgrade")!=0,r.getInt("longhouse_upgrade")!=0,r.getInt("breaker_upgrade")!=0,r.getString("parameter")));
            }return out;});
            gardens.clear();enabledGardens.clear();
            var byCamp=new LinkedHashMap<Long,List<Cell>>();
            for(var cell:cells) {
                byCamp.computeIfAbsent(cell.camp(),k->new ArrayList<>()).add(cell);
                if(cell.command().equals("/growth")){gardens.put(cell.position(),cell.camp());if(cell.enabled())enabledGardens.add(cell.position());}
            }
            initialized.keySet().retainAll(byCamp.keySet());
            indexed.clear();indexed.addAll(byCamp.keySet());
            for(var entry:byCamp.entrySet()) {
                try {
                var state=entry.getValue().getFirst();int flags=(state.enabled()?1:0)+(state.longhouse()?2:0)+(state.sifter()?4:0);
                if(Objects.equals(initialized.get(entry.getKey()),flags))continue;
                boolean pending=storage.query(db->{try{CampLonghouseStore.requireUnlocked(db,entry.getKey());return false;}catch(IllegalArgumentException reserved){return true;}});
                if(pending)continue;
                var garden=entry.getValue().stream().filter(c->Set.of("/growth","/gardensign").contains(c.command())).toList();
                if(initializeGarden(garden,state.enabled()) && initializeStations(entry.getValue()))initialized.put(entry.getKey(),flags);
                }catch(Exception failure){warn(failure);}
            }
        }catch(Exception failure){warn(failure);}
    }
    private void warn(Exception failure){long now=System.currentTimeMillis();if(now-lastWarning>30000){lastWarning=now;plugin.getLogger().warning("Camp template initialization: "+failure.getMessage());}}
    private void indexMissing()throws SQLException,IOException {
        var missing=storage.query(db->{var out=new ArrayList<Build>();try(var s=db.prepareStatement("""
                SELECT c.id AS camp_id,b.id,b.world,b.x,b.y,b.z,b.template_path
                FROM camps c JOIN structure_builds b ON c.world=b.world AND c.x=b.x AND c.y=b.y AND c.z=b.z
                WHERE b.structure_id=? AND b.status='COMPLETE' AND NOT EXISTS(SELECT 1 FROM camp_template_index i WHERE i.camp_id=c.id)
                """)) {
            s.setString(1,structureId);try(var r=s.executeQuery()){while(r.next())out.add(new Build(r.getLong("camp_id"),r.getLong("id"),r.getString("template_path"),new Position(r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"))));}
        }return out;});
        var loader=new LegacyDefTemplateLoader();
        for(var build:missing) {
            var markers=parsed.get(build.path());
            if(markers==null){markers=loader.readCommandMarkers(Path.of(build.path()),Set.of("/growth","/gardensign","/firepit","/firefurnace","/fire","/foodinput","/sifter","/control","/door"));parsed.put(build.path(),markers);}
            var selected=markers;
            storage.transaction(db->{
                try(var s=db.prepareStatement("INSERT INTO camp_template_index VALUES(?,?,?)")){s.setLong(1,build.camp());s.setLong(2,build.id());s.setString(3,build.path());s.executeUpdate();}
                try(var s=db.prepareStatement("INSERT INTO camp_template_markers VALUES(?,?,?,?,?,?,?,?)")) {
                    for(var m:selected){s.setLong(1,build.camp());s.setString(2,build.origin().world());s.setInt(3,Math.addExact(build.origin().x(),m.x()));s.setInt(4,Math.addExact(build.origin().y(),m.y()));s.setInt(5,Math.addExact(build.origin().z(),m.z()));s.setString(6,m.signLines().getFirst().trim().toLowerCase(Locale.ROOT));s.setInt(7,Byte.toUnsignedInt(m.legacyData()));s.setString(8,m.signLines().size()>1?m.signLines().get(1):"");s.addBatch();}s.executeBatch();
                }return null;
            });
        }
    }
    private boolean initializeGarden(List<Cell> cells,boolean enabled) {
        for(var cell:cells){var p=cell.position();var world=Bukkit.getWorld(p.world());if(world==null || !world.isChunkLoaded(p.x()>>4,p.z()>>4))return false;}
        var worlds=new HashSet<World>();
        for(var cell:cells) {
            var p=cell.position();var world=Bukkit.getWorld(p.world());var block=world.getBlockAt(p.x(),p.y(),p.z());
            worlds.add(world);
            if(cell.command().equals("/growth")) {
                if(!enabled && block.getState() instanceof Sign){block.setType(Material.AIR,false);worlds.add(world);}
                if(enabled && block.getType()!=Material.FARMLAND) {
                    if(block.getState() instanceof Container)throw new IllegalStateException("Container occupies garden soil: "+p);
                    block.setType(Material.FARMLAND,false);var soil=(Farmland)block.getBlockData();soil.setMoisture(soil.getMaximumMoisture());block.setBlockData(soil,false);worlds.add(world);
                }
            }else if(enabled) {
                if(block.getState() instanceof Sign){block.setType(Material.AIR,false);worlds.add(world);}
            }else {
                if(block.getState() instanceof Container)throw new IllegalStateException("Container occupies garden sign: "+p);
                block.setType(Material.OAK_SIGN,false);var data=(Rotatable)block.getBlockData();data.setRotation(rotation(cell.data()));block.setBlockData(data,false);
                var sign=(Sign)block.getState();sign.setLine(0,"Сад не куплен");sign.setLine(1,"Купить улучшение:");sign.setLine(2,"/camp upgrade");sign.setLine(3,"garden");sign.update(false,false);worlds.add(world);
            }
        }
        for(var world:worlds)world.save(true);
        return true;
    }
    private boolean initializeStations(List<Cell> cells)throws SQLException {
        var stations=cells.stream().filter(c->Set.of("/foodinput","/sifter","/control","/door","/firefurnace").contains(c.command())).toList();
        for(var cell:stations){var p=cell.position();var world=Bukkit.getWorld(p.world());if(world==null || !world.isChunkLoaded(p.x()>>4,p.z()>>4))return false;}
        var worlds=new HashSet<World>();
        for(var cell:stations) {
            var p=cell.position();var world=Bukkit.getWorld(p.world());var block=world.getBlockAt(p.x(),p.y(),p.z());
            worlds.add(world);
            if(cell.command().equals("/door")) {
                if(block.getState() instanceof Sign || block.getType().isAir()) {
                    var upper=block.getRelative(BlockFace.UP);
                    if(upper.getState() instanceof Container)throw new IllegalStateException("Container occupies camp door: "+p);
                    var bottom=(org.bukkit.block.data.type.Door)Material.OAK_DOOR.createBlockData();
                    bottom.setFacing(switch(cell.data()&15){case 4->BlockFace.EAST;case 8->BlockFace.SOUTH;case 12->BlockFace.WEST;default->BlockFace.NORTH;});
                    bottom.setHalf(org.bukkit.block.data.Bisected.Half.BOTTOM);var top=(org.bukkit.block.data.type.Door)bottom.clone();top.setHalf(org.bukkit.block.data.Bisected.Half.TOP);
                    block.setBlockData(bottom,false);upper.setBlockData(top,false);worlds.add(world);
                }
                protect(cell.camp(),block);protect(cell.camp(),block.getRelative(BlockFace.UP));continue;
            }
            if(cell.command().equals("/firefurnace")) {
                if(!(block.getState() instanceof Furnace)) {
                    if(block.getState() instanceof Container)throw new IllegalStateException("Container occupies camp furnace: "+p);
                    var data=(org.bukkit.block.data.Directional)Material.FURNACE.createBlockData();data.setFacing(rotation(cell.data()));block.setBlockData(data,false);worlds.add(world);
                }
                protect(cell.camp(),block);continue;
            }
            String prefix;
            if(cell.command().equals("/control")) {
                if(block.getState() instanceof Sign) {
                    if(block.getRelative(BlockFace.UP).getState() instanceof Container)throw new IllegalStateException("Container occupies camp control block: "+p);
                    block.setType(Material.OAK_FENCE,false);block.getRelative(BlockFace.UP).setType(Material.OBSIDIAN,false);worlds.add(world);
                }
                protect(cell.camp(),block);protect(cell.camp(),block.getRelative(BlockFace.UP));
                prefix="control_block";p=new Position(p.world(),p.x(),p.y()+1,p.z());
            }else {
                boolean food=cell.command().equals("/foodinput");boolean enabled=food?cell.longhouse():cell.sifter();
                prefix=food?"food_chest":cell.parameter().trim().equalsIgnoreCase("id:0")?"breaker_input":cell.parameter().trim().equalsIgnoreCase("id:1")?"breaker_output":null;
                if(prefix==null)throw new IllegalArgumentException("Unknown camp sifter ID: "+cell.parameter());
                if(enabled && !(block.getState() instanceof Chest)) {
                    if(block.getState() instanceof Container)throw new IllegalStateException("Container occupies camp workstation: "+p);
                    StructureChestBlocks.place(block,(byte)cell.data());worlds.add(world);
                }else if(!enabled && block.getState() instanceof Sign sign) {
                    sign.setLine(0,food?"Большой дом":"Просеиватель");sign.setLine(1,"не куплен");sign.setLine(2,"/camp upgrade");sign.setLine(3,food?"longhouse":"sifter");sign.update(false,false);worlds.add(world);
                }
                protect(cell.camp(),block);
            }
            var position=p;var column=prefix;
            storage.transaction(db->{try(var s=db.prepareStatement("UPDATE camps SET "+column+"_world=?,"+column+"_x=?,"+column+"_y=?,"+column+"_z=? WHERE id=?")) {
                s.setString(1,position.world());s.setInt(2,position.x());s.setInt(3,position.y());s.setInt(4,position.z());s.setLong(5,cell.camp());s.executeUpdate();
            }return null;});
        }
        for(var world:worlds)world.save(true);
        return true;
    }
    private void protect(long camp,Block block)throws SQLException {
        storage.transaction(db->{try(var s=db.prepareStatement("""
                INSERT INTO protected_blocks(world,x,y,z,build_id,material)
                SELECT ?,?,?,?,build_id,? FROM camp_template_index WHERE camp_id=?
                ON CONFLICT(world,x,y,z) DO UPDATE SET material=excluded.material WHERE protected_blocks.build_id=excluded.build_id
                """)) {
            s.setString(1,block.getWorld().getName());s.setInt(2,block.getX());s.setInt(3,block.getY());s.setInt(4,block.getZ());s.setString(5,block.getType().name());s.setLong(6,camp);
            if(s.executeUpdate()!=1)throw new SQLException("Camp marker protection conflicts with another structure");
        }return null;});
    }
    private static BlockFace rotation(int data){return switch(data&15){case 4->BlockFace.WEST;case 8->BlockFace.NORTH;case 12->BlockFace.EAST;default->BlockFace.SOUTH;};}
    private static Position at(Block b){return new Position(b.getWorld().getName(),b.getX(),b.getY(),b.getZ());}
    public boolean soil(Block block){return gardens.containsKey(at(block));}
    public boolean indexed(long camp){return indexed.contains(camp);}
    public boolean allowsGrowth(Block block){return enabledGardens.contains(at(block)) || enabledGardens.contains(at(block.getRelative(BlockFace.DOWN)));}
    public boolean gardenCell(Block block){return soil(block) || soil(block.getRelative(BlockFace.DOWN));}
    public boolean editable(Player player,Block block) {
        var p=at(block);if(!enabledGardens.contains(p))p=at(block.getRelative(BlockFace.DOWN));
        if(!enabledGardens.contains(p))return false;
        Long camp=gardens.get(p);if(camp==null)return false;
        try{return storage.query(db->{try(var s=db.prepareStatement("SELECT 1 FROM camp_members WHERE camp_id=? AND uuid=?")){s.setLong(1,camp);s.setString(2,player.getUniqueId().toString());try(var r=s.executeQuery()){return r.next();}}});}
        catch(SQLException failure){return false;}
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(gardenCell(e.getBlock()) && !editable(e.getPlayer(),e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void place(BlockPlaceEvent e){if(gardenCell(e.getBlock()) && !editable(e.getPlayer(),e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void trample(EntityChangeBlockEvent e){if(soil(e.getBlock()))e.setCancelled(true);}
    private record Build(long camp,long id,String path,Position origin) {}
    private record Cell(long camp,Position position,String command,int data,boolean enabled,boolean longhouse,boolean sifter,String parameter) {}
}
