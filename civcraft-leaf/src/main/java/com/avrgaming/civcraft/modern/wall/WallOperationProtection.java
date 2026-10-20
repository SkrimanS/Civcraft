package com.avrgaming.civcraft.modern.wall;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import java.sql.SQLException;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.*;

/** Early operational barrier; final explosion checks follow the legacy wartime HIGH reset. */
public final class WallOperationProtection implements Listener {
    private final CivCraftModernPlugin plugin;
    private long lastWarning;
    public WallOperationProtection(CivCraftModernPlugin plugin) {
        this.plugin=plugin;plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    private boolean locked(Block block) {
        if(block==null)return false;
        try{return plugin.storage().query(db->WallStore.reserved(db,block.getWorld().getName(),block.getX(),block.getY(),block.getZ()));}
        catch(SQLException failure){long now=System.currentTimeMillis();if(now-lastWarning>=30000){lastWarning=now;plugin.getLogger().severe("Cannot read pending wall protection: "+failure.getMessage());}return true;}
    }
    private boolean locked(Inventory inventory){return locked(inventory.getHolder());}
    private boolean locked(InventoryHolder holder) {
        return holder instanceof BlockState block?locked(block.getBlock()):holder instanceof DoubleChest pair && (locked(pair.getLeftSide()) || locked(pair.getRightSide()));
    }
    private void notice(Player player){player.sendMessage("§cЭти блоки временно заняты установкой или восстановлением стены.");}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){if(locked(e.getBlock())){e.setCancelled(true);notice(e.getPlayer());}}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void place(BlockPlaceEvent e){if(locked(e.getBlock()) || e instanceof BlockMultiPlaceEvent multi && multi.getReplacedBlockStates().stream().anyMatch(b->locked(b.getBlock()))){e.setCancelled(true);notice(e.getPlayer());}}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void physics(BlockPhysicsEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void fertilize(BlockFertilizeEvent e){if(e.getBlocks().stream().anyMatch(b->locked(b.getBlock())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void tree(StructureGrowEvent e){if(e.getBlocks().stream().anyMatch(b->locked(b.getBlock())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(locked(e.getClickedBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void empty(PlayerBucketEmptyEvent e){if(locked(e.getBlock()) || locked(e.getBlockClicked().getRelative(e.getBlockFace())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void fill(PlayerBucketFillEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void fade(BlockFadeEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void grow(BlockGrowEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void spread(BlockSpreadEvent e){if(locked(e.getBlock()) || locked(e.getSource()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void form(BlockFormEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void burn(BlockBurnEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void ignite(BlockIgniteEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void flow(BlockFromToEvent e){if(locked(e.getBlock()) || locked(e.getToBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void entity(EntityChangeBlockEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(EntityExplodeEvent e){if(e.blockList().stream().anyMatch(this::locked))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void explode(BlockExplodeEvent e){if(e.blockList().stream().anyMatch(this::locked))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void piston(BlockPistonExtendEvent e){if(locked(e.getBlock()) || locked(e.getBlock().getRelative(e.getDirection())) || e.getBlocks().stream().anyMatch(b->locked(b) || locked(b.getRelative(e.getDirection()))))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void piston(BlockPistonRetractEvent e){if(locked(e.getBlock()) || locked(e.getBlock().getRelative(e.getDirection())) || e.getBlocks().stream().anyMatch(b->locked(b) || locked(b.getRelative(e.getDirection()))))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void open(InventoryOpenEvent e){if(locked(e.getInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void click(InventoryClickEvent e){if(locked(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(locked(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void move(InventoryMoveItemEvent e){if(locked(e.getSource()) || locked(e.getDestination()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void pickup(InventoryPickupItemEvent e){if(locked(e.getInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void fuel(FurnaceBurnEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void smelt(FurnaceSmeltEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void brew(BrewEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void leaves(LeavesDecayEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void sponge(SpongeAbsorbEvent e){if(locked(e.getBlock()) || e.getBlocks().stream().anyMatch(b->locked(b.getBlock())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void moisture(MoistureChangeEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void cauldron(CauldronLevelChangeEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void harvest(PlayerHarvestBlockEvent e){if(locked(e.getHarvestedBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST) public void redstone(BlockRedstoneEvent e){if(locked(e.getBlock()))e.setNewCurrent(e.getOldCurrent());}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void cook(BlockCookEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void brewingFuel(BrewingStandFuelEvent e){if(locked(e.getBlock()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) public void dispense(BlockDispenseEvent e){
        if(locked(e.getBlock()) || e.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional facing && locked(e.getBlock().getRelative(facing.getFacing())))e.setCancelled(true);
    }
}
