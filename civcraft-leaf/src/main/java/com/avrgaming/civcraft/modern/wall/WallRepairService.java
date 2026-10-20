package com.avrgaming.civcraft.modern.wall;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.trade.GoodieChestStore;
import com.avrgaming.civcraft.modern.war.*;
import java.sql.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;

public final class WallRepairService {
    private final CivCraftModernPlugin plugin;
    private final WallCatalog catalog;
    private final WallPlacement placement;
    public WallRepairService(CivCraftModernPlugin plugin,WallCatalog catalog){this.plugin=plugin;this.catalog=catalog;placement=new WallPlacement(plugin);}
    private void checkWar(Connection db)throws SQLException {
        try{WarClockStore.requireDiplomacyOpen(db,plugin.warClock().calendar(),System.currentTimeMillis());}
        catch(IllegalArgumentException blocked){throw new IllegalArgumentException("Во время боевого периода ремонт стен запрещён.");}
    }
    public void command(Player player,long build,boolean confirmed)throws SQLException {
        if(plugin.isWallPending(player.getUniqueId()) || plugin.isFoundationPending(player.getUniqueId()) || plugin.isWarCampPending(player.getUniqueId())
                || plugin.roads().hasPending(player.getUniqueId()) || plugin.legacyStructures().hasPreview(player))throw new IllegalArgumentException("Сначала завершите размещение постройки или отмените разметку.");
        var wall=plugin.storage().query(db->WallRemovalStore.wall(db,build));var profile=catalog.profile(wall.type());
        var offer=plugin.storage().query(db->{checkWar(db);return WallRepairStore.offer(db,build,player.getUniqueId(),profile);});
        if(!confirmed){player.sendMessage("Ремонт стены #"+build+" стоит "+offer.cost()+" монет из казны. Подтвердите: /b repairwall "+build+" yes");return;}
        var world=Bukkit.getWorld(wall.world());if(world==null)throw new IllegalArgumentException("Мир стены недоступен.");
        var cells=plugin.storage().query(db->WallRemovalStore.cells(db,build));
        var images=plugin.storage().query(db->{
            placement.validateRepairColumns(db,world,build,cells);
            var plants=SiegeWorldStore.productionLocks(db,wall.world());var positions=new ArrayList<GoodieChestStore.Position>();
            var result=new ArrayList<WallStore.Image>();var chunks=new HashSet<Long>();
            for(var cell:cells) {
                var p=cell.point();long chunk=((long)(p.x()>>4)<<32)^((p.z()>>4)&0xffffffffL);
                if(chunks.add(chunk) && plugin.storage().isActiveStructureChunk(wall.world(),p.x()>>4,p.z()>>4))throw new IllegalArgumentException("Рядом ещё выполняется строительный журнал.");
                SiegeWorldStore.requireAreaFree(db,wall.world(),p.x(),p.y(),p.z(),1,1,1);
                if(plants.contains(new SiegeWorldStore.Position(p.x(),p.y(),p.z())))throw new IllegalArgumentException("На месте стены ещё сохраняются растения.");
                positions.add(new GoodieChestStore.Position(wall.world(),p.x(),p.y(),p.z()));
                result.add(new WallStore.Image(p,Bukkit.createBlockData(cell.after()).getAsString(),null));
            }
            GoodieChestStore.requirePositionsAvailable(db,positions);return List.copyOf(result);
        });
        plugin.storage().transaction(db->{checkWar(db);WallRepairStore.prepare(db,"wall-repair:"+UUID.randomUUID(),build,player.getUniqueId(),profile,images,System.currentTimeMillis());return null;});
        player.sendMessage("Ремонт стены #"+build+" начат. Из казны списано "+offer.cost()+" монет.");
    }
}
