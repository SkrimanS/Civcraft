package com.avrgaming.civcraft.modern.items;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.domain.ResidentProfile;
import com.avrgaming.civcraft.modern.foundation.FoundationItemFactory;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

/** Native implementation of the original material/recipe data; no MMOItems required. */
public final class NativeLegacyItems implements Listener {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final NamespacedKey identity;
    private final LegacyItemMaterials materials;
    private final Map<String,Definition> definitions=new LinkedHashMap<>();
    private final List<LegacyRecipe> recipes=new ArrayList<>();
    private final List<NamespacedKey> recipeKeys=new ArrayList<>();
    private final Map<String,Color> leatherColors=new HashMap<>();
    private final Set<String> placeable=new HashSet<>();
    private final Set<String> tradeable=new HashSet<>();
    private final Map<String,CombatProperties> combat=new HashMap<>();
    private final Map<String,Set<String>> components=new HashMap<>();
    public record CombatProperties(double rangedAttack, double deathDamage) {}
    private final LegacyComponentSerializer colors=LegacyComponentSerializer.legacySection();
    public record Definition(String id, Material material, String name, String category,
                             List<String> requiredTechs, int amount, boolean unbreakable,
                             double attack, double defense, double tradeValue, List<String> lore) { }
    public NativeLegacyItems(CivCraftModernPlugin plugin,StorageBootstrap storage) throws IOException {
        this.plugin=plugin;this.storage=storage;identity=new NamespacedKey(plugin,"material-id");
        materials=new LegacyItemMaterials(plugin.getResource("legacy-materials.properties"));
        reload();
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    public void reload() {
        for(NamespacedKey key:recipeKeys)Bukkit.removeRecipe(key);
        recipeKeys.clear();definitions.clear();recipes.clear();leatherColors.clear();placeable.clear();tradeable.clear();combat.clear();components.clear();
        YamlConfiguration yaml=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/materials.yml"));
        List<Map<?,?>> raw=yaml.getMapList("materials");
        for(Map<?,?> item:raw) {
            String id=text(item.get("id"));
            if(id.isBlank())continue;
            if(Boolean.parseBoolean(text(item.get("tradeable"))))tradeable.add(id);
            double attack=0,defense=0,rangedAttack=0,deathDamage=0;boolean unbreakable=false;
            var names=new HashSet<String>();
            if(item.get("components") instanceof List<?> components)for(Object value:components)if(value instanceof Map<?,?> component) {
                names.add(text(component.get("name")));
                switch(text(component.get("name"))) {
                    case "Attack" -> attack=number(component.get("value"),0);
                    case "Defense" -> defense=number(component.get("value"),0);
                    case "RangedAttack" -> rangedAttack=number(component.get("value"),0);
                    case "DurabilityOnDeath" -> deathDamage=number(component.get("value"),0);
                    case "NoVanillaDurability" -> unbreakable=true;
                    case "LeatherColor" -> leatherColors.put(id,Color.fromRGB(Integer.parseInt(text(component.get("color")),16)));
                    case "AllowBlockPlace" -> placeable.add(id);
                    default -> { }
                }
            }
            this.components.put(id,Set.copyOf(names));
            combat.put(id,new CombatProperties(rangedAttack,deathDamage));
            List<String> lore=item.get("lore") instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of();
            definitions.put(id,new Definition(id,materials.resolve((int)number(item.get("item_id"),0),(int)number(item.get("item_data"),0)),
                text(item.get("name")),text(item.get("category")),requiredTechnologies(item.get("required_techs")),
                Math.max(1,(int)number(item.get("craft_amount"),1)),unbreakable,attack,defense,number(item.get("tradeValue"),0),lore));
        }
        for(Map<?,?> item:raw) {
            String id=text(item.get("id"));
            if(!definitions.containsKey(id) || !Boolean.parseBoolean(text(item.get("craftable"))))continue;
            if(!(item.get("ingredients") instanceof List<?> ingredients) || ingredients.isEmpty())continue;
            List<String> shape=Boolean.parseBoolean(text(item.get("shaped"))) && item.get("shape") instanceof List<?> list?list.stream().map(String::valueOf).toList():List.of();
            Map<Character,LegacyRecipe.Ingredient> symbols=new LinkedHashMap<>();List<LegacyRecipe.Ingredient> shapeless=new ArrayList<>();
            Map<Character,RecipeChoice> choices=new LinkedHashMap<>();List<RecipeChoice> shapelessChoices=new ArrayList<>();
            for(Object value:ingredients) {
                Map<?,?> ingredient=(Map<?,?>)value;
                String custom=text(ingredient.get("custom_id"));
                List<Material> kinds=custom.isBlank()?materials.choices((int)number(ingredient.get("type_id"),0),(int)number(ingredient.get("data"),0)):List.of(require(custom).material());
                LegacyRecipe.Ingredient token=new LegacyRecipe.Ingredient(custom.isBlank()?null:custom,kinds.stream().map(Material::name).collect(java.util.stream.Collectors.toSet()));
                RecipeChoice choice=new RecipeChoice.MaterialChoice(kinds);
                if(!shape.isEmpty()) {
                    String letter=text(ingredient.get("letter"));if(letter.length()!=1)throw new IllegalArgumentException("Некорректный символ рецепта "+id);
                    symbols.put(letter.charAt(0),token);choices.put(letter.charAt(0),choice);
                } else for(int i=0;i<Math.max(1,(int)number(ingredient.get("count"),1));i++){shapeless.add(token);shapelessChoices.add(choice);}
            }
            LegacyRecipe recipe=new LegacyRecipe(id,shape,Map.copyOf(symbols),List.copyOf(shapeless));
            NamespacedKey key=new NamespacedKey(plugin,"legacy_"+id.toLowerCase(Locale.ROOT));
            Recipe bukkit;
            if(shape.isEmpty()) {ShapelessRecipe r=new ShapelessRecipe(key,create(id,require(id).amount()));for(RecipeChoice choice:shapelessChoices)r.addIngredient(choice);bukkit=r;}
            else {ShapedRecipe r=new ShapedRecipe(key,create(id,require(id).amount()));r.shape(shape.toArray(String[]::new));choices.forEach(r::setIngredient);bukkit=r;}
            if(!Bukkit.addRecipe(bukkit))throw new IllegalArgumentException("Рецепт не зарегистрирован: "+id);
            recipeKeys.add(key);recipes.add(recipe);
        }
        plugin.getLogger().info("Native CivCraft items: "+definitions.size()+", original recipes: "+recipes.size());
    }
    public Definition require(String id) {Definition definition=definitions.get(id);if(definition==null)throw new IllegalArgumentException("Неизвестный предмет: "+id);return definition;}
    public List<Definition> list() {return List.copyOf(definitions.values());}
    public List<LegacyRecipe> recipes() {return List.copyOf(recipes);}
    public Optional<Definition> definition(ItemStack stack) {return Optional.ofNullable(definitions.get(id(stack)));}
    public boolean tradeable(ItemStack stack) {return tradeable.contains(id(stack));}
    public CombatProperties combat(String id) {return combat.getOrDefault(id,new CombatProperties(0,0));}
    public ItemStack vanilla(int legacyId,int quantity) {return new ItemStack(materials.resolve(legacyId,0),quantity);}
    public ItemStack vanilla(int legacyId,int data,int quantity) {return new ItemStack(materials.resolve(legacyId,data),quantity);}
    public ItemStack create(String id,int quantity) {
        if(quantity<1 || quantity>64)throw new IllegalArgumentException("Количество должно быть от 1 до 64.");
        Definition definition=require(id);
        ItemStack stack=switch(id) {
            case "mat_found_camp" -> FoundationItemFactory.create(plugin,FoundationItemFactory.Kind.CAMP,quantity);
            case "mat_found_civ" -> FoundationItemFactory.create(plugin,FoundationItemFactory.Kind.CIVILIZATION,quantity);
            default -> new ItemStack(definition.material(),quantity);
        };
        ItemMeta meta=stack.getItemMeta();meta.displayName(display(definition.name()));
        if(meta instanceof LeatherArmorMeta leather && leatherColors.containsKey(id))leather.setColor(leatherColors.get(id));
        List<Component> lore=new ArrayList<>();if(!definition.category().isBlank())lore.add(display(definition.category()));
        for(String line:definition.lore())lore.add(display(line));
        if(id.equals("mat_found_warcamp"))lore.add(display("&eПравый клик: основать военный лагерь во время боя."));
        if(definition.attack()>0)lore.add(display("&cУрон: "+definition.attack()));
        if(definition.defense()>0)lore.add(display("&9Защита: "+definition.defense()));
        if(combat(id).rangedAttack()>0)lore.add(display("&cУрон стрел: "+combat(id).rangedAttack()));
        if(combat(id).deathDamage()>0)lore.add(display("&7Потеря прочности при смерти: "+Math.round(combat(id).deathDamage()*100)+"%"));
        meta.lore(lore);
        meta.setUnbreakable(definition.unbreakable());meta.getPersistentDataContainer().set(identity,PersistentDataType.STRING,id);
        if(id.equals("mat_found_camp"))meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"soulbound"),PersistentDataType.BYTE,(byte)1);
        stack.setItemMeta(meta);return stack;
    }
    public String id(ItemStack stack) {return stack==null || !stack.hasItemMeta()?null:stack.getItemMeta().getPersistentDataContainer().get(identity,PersistentDataType.STRING);}
    public boolean hasComponent(ItemStack item,String name){String id=id(item);return id!=null && components.getOrDefault(id,Set.of()).contains(name);}
    public ItemStack tagged(ItemStack original,String tag) {
        if(!hasComponent(original,"Tagged") || tag==null || tag.isBlank())throw new IllegalArgumentException("Требуется предмет с меткой лагеря.");
        var item=original.clone();var meta=item.getItemMeta();meta.getPersistentDataContainer().set(new NamespacedKey(plugin,"leadership-tag"),PersistentDataType.STRING,tag);
        String leader=tag;try{String name=Bukkit.getOfflinePlayer(UUID.fromString(tag)).getName();if(name!=null)leader=name;}catch(IllegalArgumentException ignored){}
        var lore=meta.lore()==null?new ArrayList<Component>():new ArrayList<>(meta.lore());lore.removeIf(line->net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line).startsWith("Лидер: "));lore.add(Component.text("Лидер: "+leader));meta.lore(lore);item.setItemMeta(meta);return item;
    }
    private String craftTag(ItemStack[] matrix) {
        String tag=null;for(var item:matrix)if(item!=null && !item.getType().isAir()) {
            if(!hasComponent(item,"Tagged"))return null;
            String next=item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin,"leadership-tag"),PersistentDataType.STRING);
            if(next==null || next.isBlank())return null;if(tag==null)tag=next;else if(!tag.equals(next))return null;
        }return tag;
    }
    private List<LegacyRecipe.Token> tokens(ItemStack[] matrix) {
        List<LegacyRecipe.Token> result=new ArrayList<>();
        for(ItemStack item:matrix)result.add(item==null || item.getType().isAir()?null:new LegacyRecipe.Token(id(item),item.getType().name()));
        return result;
    }
    public Optional<LegacyRecipe> match(ItemStack[] matrix) {
        int width=matrix.length==4?2:3;List<LegacyRecipe.Token> tokens=tokens(matrix);
        return recipes.stream().filter(r->r.matches(tokens,width)).findFirst();
    }
    public boolean hasTechnology(Player player,Definition item) throws SQLException {
        if(item.requiredTechs().isEmpty())return true;
        ResidentProfile resident=storage.findResident(player.getUniqueId()).orElse(null);
        return resident!=null && resident.civId()!=null && storage.researchedTechs(resident.civId()).containsAll(item.requiredTechs());
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void prepare(PrepareItemCraftEvent event) {
        ItemStack[] matrix=event.getInventory().getMatrix();Optional<LegacyRecipe> found=match(matrix);
        if(found.isPresent()) {
            Definition definition=require(found.get().id());
            try {
                if(!(event.getView().getPlayer() instanceof Player player) || !hasTechnology(player,definition)){event.getInventory().setResult(null);return;}
                ItemStack result=create(definition.id(),1);result.setAmount(definition.amount());
                if(hasComponent(result,"Tagged")){String tag=craftTag(matrix);if(tag==null){event.getInventory().setResult(null);return;}result=tagged(result,tag);}
                event.getInventory().setResult(result);
            } catch(SQLException exception){event.getInventory().setResult(null);plugin.getLogger().warning("Craft research check failed: "+exception.getMessage());}
        } else if(Arrays.stream(matrix).anyMatch(item->id(item)!=null)
                || (event.getRecipe()!=null && id(event.getRecipe().getResult())!=null)) {
            event.getInventory().setResult(null);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void craft(CraftItemEvent event) {
        String result=id(event.getCurrentItem());if(result==null)return;
        if(hasComponent(event.getCurrentItem(),"Tagged")) {
            String tag=craftTag(event.getInventory().getMatrix());String actual=event.getCurrentItem().getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin,"leadership-tag"),PersistentDataType.STRING);
            if(tag==null || !tag.equals(actual)){event.setCancelled(true);return;}
        }
        try {if(!(event.getWhoClicked() instanceof Player player) || match(event.getInventory().getMatrix()).filter(r->r.id().equals(result)).isEmpty() || !hasTechnology(player,require(result)))event.setCancelled(true);}
        catch(SQLException exception){event.setCancelled(true);}
    }
    @EventHandler(ignoreCancelled=true)
    public void preventPlacement(BlockPlaceEvent event) {
        String id=id(event.getItemInHand());
        if(id!=null && !placeable.contains(id))event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true)
    public void durability(PlayerItemDamageEvent event) {String id=id(event.getItem());if(id!=null && require(id).unbreakable())event.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)
    public void rightClick(org.bukkit.event.player.PlayerInteractEvent event) {
        var action=event.getAction();if(action!=org.bukkit.event.block.Action.RIGHT_CLICK_AIR && action!=org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)return;
        var item=event.getItem();var block=event.getClickedBlock();
        if(hasComponent(item,"NoCauldronWash") && block!=null && block.getType().name().endsWith("CAULDRON")){event.setCancelled(true);return;}
        if(!hasComponent(item,"NoRightClick"))return;
        // Keep plugin sign actions available while denying the vanilla item action.
        if(block!=null && plugin.trades()!=null && plugin.trades().isPublicTradePoint(block)){event.setUseItemInHand(Event.Result.DENY);return;}
        if(block==null || block.getType()!=Material.CHEST)event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void entityUse(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if(hasComponent(event.getPlayer().getInventory().getItem(event.getHand()),"NoRightClick"))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void resetDurability(InventoryOpenEvent event) {
        resetDurability(event.getInventory());resetDurability(event.getPlayer().getInventory());
    }
    private void resetDurability(Inventory inventory) {
        for(int slot=0;slot<inventory.getSize();slot++) {
            var item=inventory.getItem(slot);if(!hasComponent(item,"NoDurability") || !(item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable meta))continue;
            if(meta.getDamage()==0 && !meta.isUnbreakable())continue;meta.setDamage(0);meta.setUnbreakable(false);item.setItemMeta(meta);inventory.setItem(slot,item);
        }
    }
    @EventHandler
    public void discover(PlayerJoinEvent event) {for(NamespacedKey key:recipeKeys)event.getPlayer().discoverRecipe(key);}
    private static String text(Object value){return value==null?"":value.toString().trim();}
    private Component display(String text) {
        String value=text;
        Map<String,String> codes=Map.ofEntries(
                Map.entry("lightgreen","a"),Map.entry("green","2"),Map.entry("gold","6"),
                Map.entry("yellow","e"),Map.entry("red","c"),Map.entry("darkred","4"),
                Map.entry("white","f"),Map.entry("gray","7"),Map.entry("lightgray","7"),
                Map.entry("blue","9"),Map.entry("lightblue","b"),Map.entry("purple","5"),
                Map.entry("lightpurple","d"),Map.entry("black","0"),Map.entry("reset","r"));
        for(var entry:codes.entrySet())value=value.replaceAll("(?i)<"+entry.getKey()+">","§"+entry.getValue());
        return colors.deserialize(ChatColor.translateAlternateColorCodes('&',value));
    }
    private static List<String> requiredTechnologies(Object value) {
        if(value==null)return List.of();
        if(value instanceof List<?> list)return list.stream().map(String::valueOf).map(String::trim).filter(s->!s.isBlank()).toList();
        return Arrays.stream(value.toString().split(",")).map(String::trim).filter(s->!s.isBlank()).toList();
    }
    private static double number(Object value,double fallback){try{return value==null?fallback:Double.parseDouble(value.toString());}catch(NumberFormatException e){return fallback;}}
}
