package com.avrgaming.civcraft.modern.town;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;

/** The original addItem storage order, without changing equipment or the cursor. */
public final class UnitInventoryLayout {
    private UnitInventoryLayout() {}
    public static boolean empty(ItemStack item){return item==null || item.getType().isAir() || item.getAmount()<=0;}
    public static List<ItemStack> keepOneUnit(ItemStack[] inventory,java.util.function.Predicate<ItemStack> unit) {
        var drops=new ArrayList<ItemStack>();boolean kept=false;
        for(int i=0;i<inventory.length;i++)if(unit.test(inventory[i])) {
            var item=inventory[i];int remove=kept?item.getAmount():Math.max(0,item.getAmount()-1);
            if(!kept){kept=true;inventory[i]=item.clone();inventory[i].setAmount(1);}else inventory[i]=null;
            while(remove>0){var drop=item.clone();int count=Math.min(remove,item.getMaxStackSize());drop.setAmount(count);drops.add(drop);remove-=count;}
        }
        return drops;
    }
    public static List<ItemStack> reposition(ItemStack[] inventory,ItemStack target) {
        return reposition(inventory,target,false);
    }
    public static List<ItemStack> reposition(ItemStack[] inventory,ItemStack target,boolean groundPickup) {
        if(empty(target))return List.of();
        for(int i=0;i<inventory.length;i++)if(!empty(inventory[i]) && inventory[i].isSimilar(target)) {
            if(i==8)return List.of();
            var displaced=inventory[8];inventory[8]=inventory[i];inventory[i]=null;
            if(groundPickup)return returnToStorage(inventory,displaced);
            if(i<36 || i==40){inventory[i]=displaced;return List.of();}
            return returnToStorage(inventory,displaced);
        }
        return List.of();
    }
    public static List<ItemStack> returnToStorage(ItemStack[] inventory,ItemStack input) {
        var drops=new ArrayList<ItemStack>();if(empty(input))return drops;
        var remaining=input.clone();int size=Math.min(36,inventory.length);
        for(int i=0;i<size && remaining.getAmount()>0;i++) {
            var current=inventory[i];if(empty(current) || !current.isSimilar(remaining))continue;
            int moved=Math.min(remaining.getAmount(),Math.max(0,current.getMaxStackSize()-current.getAmount()));
            current.setAmount(current.getAmount()+moved);remaining.setAmount(remaining.getAmount()-moved);
        }
        for(int i=0;i<size && remaining.getAmount()>0;i++)if(empty(inventory[i])) {
            int moved=Math.min(remaining.getAmount(),remaining.getMaxStackSize());
            inventory[i]=remaining.clone();inventory[i].setAmount(moved);remaining.setAmount(remaining.getAmount()-moved);
        }
        while(remaining.getAmount()>0){var drop=remaining.clone();drop.setAmount(Math.min(remaining.getAmount(),remaining.getMaxStackSize()));drops.add(drop);remaining.setAmount(remaining.getAmount()-drop.getAmount());}
        return drops;
    }
}
