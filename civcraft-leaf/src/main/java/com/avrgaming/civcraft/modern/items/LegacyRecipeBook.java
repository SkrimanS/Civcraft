package com.avrgaming.civcraft.modern.items;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** A view-only recipe browser using the same definitions as the crafting validator. */
public final class LegacyRecipeBook implements Listener {
    private final NativeLegacyItems items;
    private static final int PAGE_SIZE = 45;
    private static final int[] GRID = {12,13,14,21,22,23,30,31,32};

    private static final class Page implements InventoryHolder {
        final int number;
        final String recipe;
        Inventory inventory;
        Page(int number, String recipe) {this.number=number;this.recipe=recipe;}
        @Override public Inventory getInventory() {return inventory;}
    }

    public LegacyRecipeBook(CivCraftModernPlugin plugin, NativeLegacyItems items) {
        this.items=items;
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        plugin.getCommand("resident").setExecutor((sender,command,label,args) -> {
            if (sender instanceof Player player) {
                if(args.length==0 || args[0].equalsIgnoreCase("book")) open(player,0);
                else if(args.length==1 && (args[0].equalsIgnoreCase("biome") || args[0].equalsIgnoreCase("taxes") || args[0].equalsIgnoreCase("paydebt"))) {
                    try{if(args[0].equalsIgnoreCase("biome"))plugin.biomeSurvey().show(player);else plugin.taxes().resident(player,args[0].equalsIgnoreCase("paydebt"));}
                    catch(java.sql.SQLException failure){plugin.getLogger().warning("Resident command failed: "+failure.getMessage());player.sendMessage(Component.text("Не удалось выполнить команду жителя."));}
                    catch(IllegalArgumentException failure){player.sendMessage(Component.text(failure.getMessage()));}
                } else player.sendMessage(Component.text("Команды: /res book, /res biome, /res taxes, /res paydebt"));
            } else sender.sendMessage("Эта команда доступна в игре.");
            return true;
        });
        plugin.getCommand("resident").setTabCompleter((sender,command,label,args)->args.length==1?List.of("book","biome","taxes","paydebt"):List.of());
    }

    public void open(Player player,int number) {
        List<LegacyRecipe> recipes=items.recipes();
        int page=Math.max(0,Math.min(number,(recipes.size()-1)/PAGE_SIZE));
        Page holder=new Page(page,null);
        holder.inventory=Bukkit.createInventory(holder,54,Component.text("Рецепты CivCraft — "+(page+1)));
        for(int slot=0;slot<PAGE_SIZE && page*PAGE_SIZE+slot<recipes.size();slot++) {
            String id=recipes.get(page*PAGE_SIZE+slot).id();
            holder.inventory.setItem(slot,items.create(id,items.require(id).amount()));
        }
        if(page>0)holder.inventory.setItem(45,button(Material.ARROW,"Предыдущая страница"));
        if((page+1)*PAGE_SIZE<recipes.size())holder.inventory.setItem(53,button(Material.ARROW,"Следующая страница"));
        player.openInventory(holder.inventory);
    }

    @EventHandler public void useBook(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND)return;
        if(event.getAction()!=Action.RIGHT_CLICK_AIR && event.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        if("mat_tutorial_book".equals(items.id(event.getItem()))) {
            event.setCancelled(true);open(event.getPlayer(),0);
        }
    }

    private void show(Player player,String id,int page) {
        LegacyRecipe recipe=items.recipes().stream().filter(r->r.id().equals(id)).findFirst().orElseThrow();
        Page holder=new Page(page,id);
        holder.inventory=Bukkit.createInventory(holder,45,Component.text("Рецепт: "+items.require(id).name()));
        if(recipe.shape().isEmpty()) {
            for(int i=0;i<recipe.shapeless().size();i++)holder.inventory.setItem(GRID[i],ingredient(recipe.shapeless().get(i)));
        } else {
            for(int y=0;y<recipe.shape().size();y++)for(int x=0;x<recipe.shape().get(y).length();x++) {
                var entry=recipe.symbols().get(recipe.shape().get(y).charAt(x));
                if(entry!=null)holder.inventory.setItem(GRID[y*3+x],ingredient(entry));
            }
        }
        holder.inventory.setItem(25,items.create(id,items.require(id).amount()));
        holder.inventory.setItem(36,button(Material.ARROW,"Назад к рецептам"));
        List<String> required=items.require(id).requiredTechs();
        holder.inventory.setItem(40,button(Material.BOOK,recipe.shape().isEmpty()?"Без формы: порядок не важен":"Разложите предметы как показано"));
        if(!required.isEmpty())holder.inventory.setItem(44,button(Material.ENCHANTED_BOOK,"Требуются технологии: "+String.join(", ",required)));
        player.openInventory(holder.inventory);
    }

    private ItemStack ingredient(LegacyRecipe.Ingredient ingredient) {
        return ingredient.customId()!=null?items.create(ingredient.customId(),1):new ItemStack(Material.valueOf(ingredient.materials().stream().sorted().findFirst().orElseThrow()));
    }
    private static ItemStack button(Material material,String title) {
        ItemStack stack=new ItemStack(material);var meta=stack.getItemMeta();meta.displayName(Component.text(title));stack.setItemMeta(meta);return stack;
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Page page))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player))return;
        int slot=event.getRawSlot();
        if(page.recipe!=null) {if(slot==36)open(player,page.number);return;}
        if(slot==45 && page.number>0)open(player,page.number-1);
        else if(slot==53)open(player,page.number+1);
        else if(slot>=0 && slot<PAGE_SIZE) {
            int index=page.number*PAGE_SIZE+slot;
            if(index<items.recipes().size())show(player,items.recipes().get(index).id(),page.number);
        }
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof Page)event.setCancelled(true);
    }
}
