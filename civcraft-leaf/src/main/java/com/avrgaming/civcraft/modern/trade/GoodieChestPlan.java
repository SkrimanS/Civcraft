package com.avrgaming.civcraft.modern.trade;

import java.util.*;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;

/** Copy of the slots affected by a native click, prepared before changing either inventory. */
final class GoodieChestPlan {
    final ItemStack[] top,player;
    ItemStack cursor;
    final List<ItemStack> drops=new ArrayList<>();
    GoodieChestPlan(ItemStack[] top,ItemStack[] player,ItemStack cursor){this.top=copy(top);this.player=copy(player);this.cursor=clone(cursor);}
    static ItemStack clone(ItemStack item){return empty(item)?null:item.clone();}
    static ItemStack[] copy(ItemStack[] input){var out=input.clone();for(int i=0;i<out.length;i++)out[i]=clone(out[i]);return out;}
    static boolean empty(ItemStack item){return item==null || item.getType().isAir() || item.getAmount()==0;}
    ItemStack get(boolean upper,int slot){return upper?top[slot]:player[slot];}
    void set(boolean upper,int slot,ItemStack item){if(upper)top[slot]=clone(item);else player[slot]=clone(item);}
    void click(InventoryAction action,boolean upper,int slot,int hotbar) {
        ItemStack current=slot<0?null:get(upper,slot);
        switch(action) {
            case PICKUP_ALL,PICKUP_SOME,PICKUP_HALF,PICKUP_ONE -> {
                if(empty(current))return;
                int amount=action==InventoryAction.PICKUP_ONE?1:action==InventoryAction.PICKUP_HALF?(current.getAmount()+1)/2:current.getAmount();
                if(!empty(cursor)){if(!cursor.isSimilar(current))throw new IllegalArgumentException("Несовместимые предметы на курсоре.");amount=Math.min(amount,cursor.getMaxStackSize()-cursor.getAmount());}
                if(amount<=0)return;
                if(empty(cursor)){cursor=current.clone();cursor.setAmount(amount);}else cursor.setAmount(cursor.getAmount()+amount);
                var left=current.clone();left.setAmount(left.getAmount()-amount);set(upper,slot,left);
            }
            case PLACE_ALL,PLACE_SOME,PLACE_ONE -> {
                if(empty(cursor))return;
                if(!empty(current) && !current.isSimilar(cursor))throw new IllegalArgumentException("Несовместимые предметы в слоте.");
                int amount=Math.min(action==InventoryAction.PLACE_ONE?1:cursor.getAmount(),cursor.getMaxStackSize()-(empty(current)?0:current.getAmount()));if(amount<=0)return;
                var placed=empty(current)?cursor.clone():current.clone();placed.setAmount((empty(current)?0:current.getAmount())+amount);set(upper,slot,placed);cursor.setAmount(cursor.getAmount()-amount);cursor=clone(cursor);
            }
            case SWAP_WITH_CURSOR -> {set(upper,slot,cursor);cursor=clone(current);}
            case HOTBAR_SWAP,HOTBAR_MOVE_AND_READD -> {if(hotbar<0 || hotbar>=player.length)throw new IllegalArgumentException("Недоступный слот обмена.");var other=clone(player[hotbar]);set(upper,slot,other);player[hotbar]=clone(current);}
            case MOVE_TO_OTHER_INVENTORY -> {
                if(empty(current))return;
                var target=upper?player:top;var moving=current.clone();
                var order=new ArrayList<Integer>();if(upper){for(int i=8;i>=0;i--)order.add(i);for(int i=35;i>=9;i--)order.add(i);}else for(int i=0;i<top.length;i++)order.add(i);
                for(int index:order)if(!empty(target[index]) && target[index].isSimilar(moving)){int n=Math.min(moving.getAmount(),Math.max(0,moving.getMaxStackSize()-target[index].getAmount()));target[index].setAmount(target[index].getAmount()+n);moving.setAmount(moving.getAmount()-n);}
                for(int index:order)if(empty(target[index]) && moving.getAmount()>0){int n=Math.min(moving.getAmount(),moving.getMaxStackSize());target[index]=moving.clone();target[index].setAmount(n);moving.setAmount(moving.getAmount()-n);}
                set(upper,slot,moving);
            }
            case DROP_ALL_CURSOR,DROP_ONE_CURSOR -> {
                if(empty(cursor))return;var drop=cursor.clone();drop.setAmount(action==InventoryAction.DROP_ONE_CURSOR?1:cursor.getAmount());drops.add(drop);cursor.setAmount(cursor.getAmount()-drop.getAmount());cursor=clone(cursor);
            }
            case DROP_ALL_SLOT,DROP_ONE_SLOT -> {
                if(empty(current))return;var drop=current.clone();drop.setAmount(action==InventoryAction.DROP_ONE_SLOT?1:current.getAmount());drops.add(drop);var left=current.clone();left.setAmount(left.getAmount()-drop.getAmount());set(upper,slot,left);
            }
            case NOTHING -> {}
            default -> throw new IllegalArgumentException("Это действие с торговым товаром недоступно.");
        }
    }
}
