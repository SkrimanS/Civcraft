package com.avrgaming.civcraft.modern.campnpc.reward;

import com.avrgaming.civcraft.modern.campnpc.bridge.CivCraftBridge;
import com.avrgaming.civcraft.modern.campnpc.item.CivCraftItemService;
import com.avrgaming.civcraft.modern.campnpc.quest.QuestDefinition;
import com.avrgaming.civcraft.modern.campnpc.quest.QuestReward;
import com.avrgaming.civcraft.modern.campnpc.storage.PluginStorage;
import com.avrgaming.civcraft.modern.campnpc.util.ItemsAdderHook;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class QuestRewardService {
    private final JavaPlugin plugin;
    private final PluginStorage storage;
    private final CivCraftBridge civCraft;
    private final CivCraftItemService customItems;
    private final ItemsAdderHook itemsAdder = new ItemsAdderHook();
    private final MmoItemsHook mmoItems = new MmoItemsHook();
    private long lastExternalWarning;

    public QuestRewardService(JavaPlugin plugin, PluginStorage storage, CivCraftBridge civCraft, CampBonusApplier bonusApplier, CivCraftItemService customItems) {
        this.plugin = plugin;
        this.storage = storage;
        this.civCraft = civCraft;
        this.customItems = customItems;
        if(plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin)try(var connection=storage.connect();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT operation_id,ordinal,status,'command' AS kind FROM camp_quest_reward_commands WHERE status IN ('CLAIMED','FAILED') UNION ALL SELECT operation_id,ordinal,status,'payment' AS kind FROM camp_quest_reward_payments WHERE status IN ('CLAIMED','FAILED')")) {
            while(rows.next())plugin.getLogger().warning("NPC external reward requires review: "+rows.getString("kind")+" "+rows.getString("operation_id")+"/"+rows.getInt("ordinal")+" "+rows.getString("status"));
        }catch(SQLException failure){throw new IllegalStateException("Cannot inspect NPC external reward receipts",failure);}
    }

    public void give(Player completer, long campId, QuestDefinition quest)throws SQLException {
        try(var connection=storage.connect()){if(QuestRewardStore.exists(connection,campId,quest.id()))return;}
        if(!civCraft.canApplyCampQuest(campId,completer.getUniqueId()))throw new IllegalStateException("Camp reward inventories are still being recovered");
        if(!(plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin modern) || modern.goodieTransfers()==null)throw new IllegalStateException("Camp reward journal is unavailable");
        var items=new ArrayList<ItemStack>();var effects=new ArrayList<QuestRewardStore.Effect>();var commands=new ArrayList<String>();
        for(var reward:quest.rewards())collect(completer,campId,quest,reward,items,effects,commands,modern);
        modern.goodieTransfers().prepareCampQuestReward(completer,campId,quest.id(),items,effects,commands);
    }

    private void collect(Player completer,long campId,QuestDefinition quest,QuestReward reward,List<ItemStack> items,List<QuestRewardStore.Effect> effects,List<String> commands,com.avrgaming.civcraft.modern.CivCraftModernPlugin modern) {
        String type = reward.type().trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "MONEY", "COINS", "COIN" -> effects.add(new QuestRewardStore.Effect(modern.economy().usesExternalProvider()?"VAULT":"MONEY",reward.amount(),modern.economy().externalProviderName()));
            case "CAMP_EXPERIENCE", "CAMP_XP", "EXPERIENCE", "XP" -> effects.add(new QuestRewardStore.Effect("XP",reward.amount(),""));
            case "CONTROL_HP", "CONTROL_BLOCK_HP" -> effects.add(new QuestRewardStore.Effect("HP",reward.amount(),""));
            case "UPGRADE", "CAMP_UPGRADE" -> effects.add(new QuestRewardStore.Effect("UPGRADE",reward.amount(),upgrade(reward.value())));
            case "HEALTH_BONUS", "CAMP_HEALTH" -> effects.add(new QuestRewardStore.Effect("HEALTH",reward.amount(),""));
            case "DAMAGE_BONUS", "CAMP_DAMAGE" -> effects.add(new QuestRewardStore.Effect("DAMAGE",reward.amount(),""));
            case "CIVCRAFT_ITEM", "CIVCRAFT_CUSTOM_ITEM", "CIV_ITEM", "CUSTOM_ITEM" -> items.addAll(createCivCraftItems(reward));
            case "ITEM", "VANILLA_ITEM", "ITEMSADDER", "MMOITEMS" -> items.addAll(createRewardItems(reward.item(),reward.amount(),type));
            default -> {for(var command:reward.commands())commands.add(replacePlaceholders(command,completer,campId,quest));}
        }
    }
    private static String upgrade(String value) {
        return switch(value.trim().toLowerCase(Locale.ROOT)){case "garden","farm"->"farm_upgrade";case "breaker","sifter"->"breaker_upgrade";case "longhouse"->"longhouse_upgrade";default->throw new IllegalArgumentException("Неизвестное улучшение лагеря: "+value);};
    }

    private List<ItemStack> createCivCraftItems(QuestReward reward) {
        ConfigurationSection section = reward.item();
        String id = reward.value();
        if (id == null || id.isBlank()) {
            id = section.getString("id", section.getString("item-id", section.getString("civcraft-item", "")));
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("У награды CivCraft не задан ID предмета.");
        }
        int amount = Math.max(1, section.getInt("amount", reward.amount() > 0D ? (int) Math.round(reward.amount()) : 1));
        var item = customItems.create(id, amount);
        if(item.isEmpty())throw new IllegalArgumentException("Не найден предмет награды CivCraft: "+id);
        return splitStacks(item.get());
    }

    private List<ItemStack> createRewardItems(ConfigurationSection itemSection, double fallbackAmount, String rewardType) {
        ItemStack item = createRewardItem(itemSection, rewardType);
        if (item == null || item.getType().isAir()) {
            return List.of();
        }
        int amount = Math.max(1, itemSection.getInt("amount", fallbackAmount > 0D ? (int) Math.round(fallbackAmount) : 1));
        item.setAmount(amount);
        return splitStacks(item);
    }

    private List<ItemStack> splitStacks(ItemStack item) {
        List<ItemStack> stacks = new ArrayList<>();
        int left = Math.max(1, item.getAmount());
        while (left > 0) {
            ItemStack stack = item.clone();
            int take = Math.min(stack.getMaxStackSize(), left);
            stack.setAmount(take);
            stacks.add(stack);
            left -= take;
        }
        return stacks;
    }

    private ItemStack createRewardItem(ConfigurationSection section, String rewardType) {
        String mmoType = section.getString("mmoitems-type", section.getString("mmoitems.type", section.getString("mmo-type", null)));
        String mmoId = section.getString("mmoitems-id", section.getString("mmoitems.id", section.getString("mmo-id", null)));
        if ("MMOITEMS".equals(rewardType) || (mmoType != null && mmoId != null)) {
            Optional<ItemStack> item = mmoItems.create(mmoType, mmoId);
            if (item.isPresent()) {
                return item.get();
            }
        }

        String itemsAdderId = section.getString("itemsadder", null);
        if ("ITEMSADDER".equals(rewardType) || (itemsAdderId != null && !itemsAdderId.isBlank())) {
            Optional<ItemStack> item = itemsAdder.create(itemsAdderId);
            if (item.isPresent()) {
                return item.get();
            }
        }

        Material material = Material.matchMaterial(section.getString("material", "STONE").toUpperCase(Locale.ROOT));
        ItemStack item = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (section.contains("custom-model-data")) {
                meta.setCustomModelData(section.getInt("custom-model-data"));
            }
            for (String flag : section.getStringList("flags")) {
                try {
                    meta.addItemFlags(ItemFlag.valueOf(flag.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                }
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public void flushExternalRewards()throws SQLException {
        List<QuestRewardStore.Command> commands;List<QuestRewardStore.Payment> payments;
        try(var connection=storage.connect()){commands=QuestRewardStore.waitingCommands(connection);payments=QuestRewardStore.waitingPayments(connection);}
        for(var command:commands) {
            try(var connection=storage.connect()){if(!QuestRewardStore.claimCommand(connection,command))continue;}
            boolean success=false;
            try{success=Bukkit.dispatchCommand(Bukkit.getConsoleSender(),command.command());}
            catch(RuntimeException failure){plugin.getLogger().severe("NPC reward command failed; retained for review: "+command.operation()+"/"+command.ordinal()+": "+failure.getMessage());}
            try(var connection=storage.connect()){QuestRewardStore.finishCommand(connection,command,success);}
            if(!success)plugin.getLogger().warning("NPC reward command was not completed: "+command.operation()+"/"+command.ordinal());
        }
        if(plugin instanceof com.avrgaming.civcraft.modern.CivCraftModernPlugin modern)for(var payment:payments) {
            if(!modern.economy().usesExternalProvider() || !payment.provider().equals(modern.economy().externalProviderName())){long now=System.currentTimeMillis();if(now-lastExternalWarning>30000){lastExternalWarning=now;plugin.getLogger().warning("NPC payment is waiting for economy provider "+payment.provider()+": "+payment.operation()+"/"+payment.ordinal());}continue;}
            try(var connection=storage.connect()){if(!QuestRewardStore.claimPayment(connection,payment))continue;}
            boolean success=false;
            try{modern.economy().deposit(Bukkit.getOfflinePlayer(payment.leader()),payment.amount(),"camp quest reward "+payment.operation());success=true;}
            catch(SQLException|RuntimeException failure){plugin.getLogger().severe("NPC reward payment failed; retained for review: "+payment.operation()+"/"+payment.ordinal()+": "+failure.getMessage());}
            try(var connection=storage.connect()){QuestRewardStore.finishPayment(connection,payment,success);}
        }
    }

    private String replacePlaceholders(String command, Player player, long campId, QuestDefinition quest) {
        return command
                .replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{camp_id}", String.valueOf(campId))
                .replace("{quest_id}", quest == null ? "" : quest.id())
                .replace("{quest_title}", quest == null ? "" : quest.title());
    }
}
