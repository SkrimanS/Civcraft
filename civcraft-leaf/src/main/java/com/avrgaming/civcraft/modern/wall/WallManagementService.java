package com.avrgaming.civcraft.modern.wall;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import java.sql.SQLException;
import java.util.*;
import org.bukkit.entity.Player;

/** Wall lookup uses columns, not the single-building-per-chunk index. */
public final class WallManagementService {
    private final CivCraftModernPlugin plugin;
    private final WallCatalog catalog;
    public WallManagementService(CivCraftModernPlugin plugin,WallCatalog catalog){this.plugin=plugin;this.catalog=catalog;}
    public boolean command(Player player,String[] args)throws SQLException {
        if(args.length<2)return false;String action=args[1].toLowerCase(Locale.ROOT);
        if(action.equals("wallinfo")){if(args.length!=3)throw new IllegalArgumentException("/b wallinfo <ID>");info(player,id(args[2]));return true;}
        if(action.equals("demolishwall")) {
            if(args.length!=3)throw new IllegalArgumentException("/b demolishwall <ID>");demolish(player,id(args[2]));return true;
        }
        if(action.equals("undo")) {
            var owner=plugin.storage().query(db->WallPlacement.authority(db,player.getUniqueId(),catalog.ordinary(),false));
            var last=plugin.storage().query(db->WallRemovalStore.lastWall(db,owner.town()));if(last==null)return false;
            ready(player);var wall=plugin.storage().query(db->WallRemovalStore.wall(db,last));
            long refund=plugin.storage().transaction(db->WallRemovalStore.undo(db,"wall-undo:"+UUID.randomUUID(),player.getUniqueId(),last,catalog.profile(wall.type())));
            player.sendMessage("Отмена стены #"+last+" поставлена в очередь. После возврата местности казна получит "+refund+" монет.");return true;
        }
        if(!Set.of("info","demolish").contains(action))return false;
        var at=player.getLocation();var found=plugin.storage().query(db->WallRemovalStore.at(db,at.getWorld().getName(),at.getBlockX(),at.getBlockZ()));if(found==null)return false;
        if(action.equals("info"))info(player,found);else demolish(player,found);return true;
    }
    private static long id(String text){try{long id=Long.parseLong(text);if(id>0)return id;}catch(NumberFormatException ignored){}throw new IllegalArgumentException("Укажите положительный ID стены.");}
    private void ready(Player player) {
        if(plugin.isWallPending(player.getUniqueId()) || plugin.isFoundationPending(player.getUniqueId()) || plugin.isWarCampPending(player.getUniqueId())
                || plugin.roads().hasPending(player.getUniqueId()) || plugin.legacyStructures().hasPreview(player))throw new IllegalArgumentException("Сначала завершите размещение постройки или отмените разметку.");
    }
    private void demolish(Player player,long id)throws SQLException {
        ready(player);var wall=plugin.storage().query(db->WallRemovalStore.wall(db,id));
        plugin.storage().transaction(db->{WallRemovalStore.demolish(db,"wall-demolish:"+UUID.randomUUID(),player.getUniqueId(),id,catalog.profile(wall.type()),System.currentTimeMillis());return null;});
        player.sendMessage("Стена #"+id+" снята с учёта и защиты. Блоки остаются на месте; возврата монет нет.");
    }
    private void info(Player player,long id)throws SQLException {
        var wall=plugin.storage().query(db->WallRemovalStore.wall(db,id));var p=wall.origin();
        String state=switch(wall.state()) {
            case "BUILDING" -> "строится";
            case "REMOVING" -> "возвращается местность";
            case "RETIRED" -> "снята с учёта";
            default -> wall.status().equals("WAR_DESTROYED")?"разрушена":"готова";
        };
        player.sendMessage((wall.type().equals("ti_fortifiedwall")?"Крепостная стена #":"Стена #")+id+": "+state+", "+wall.hp()+"/"+wall.maximum()+" HP.");
        player.sendMessage("Начало: "+wall.world()+" "+p.x()+", "+p.y()+", "+p.z()+". Сегментов: "+wall.segments()+", оплачено "+wall.cost()+" монет.");
    }
}
