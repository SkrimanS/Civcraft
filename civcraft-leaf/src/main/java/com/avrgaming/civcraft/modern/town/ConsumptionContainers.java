package com.avrgaming.civcraft.modern.town;

import java.io.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Physical 27-slot halves, never two copies of a double chest. Main server thread only. */
public final class ConsumptionContainers {
    private ConsumptionContainers() {}
    public record Position(String world,int x,int y,int z) {}
    public record Image(Position position,ItemStack[] contents) {
        public Image {
            if(contents.length!=27)throw new IllegalArgumentException("Expected a physical chest half");
            contents=copy(contents);
        }
        @Override public ItemStack[] contents(){return copy(contents);}
    }
    public static List<Image> capture(Collection<Position> positions) {
        requireMainThread();
        var images=new ArrayList<Image>();
        for(var position:new LinkedHashSet<>(positions))images.add(new Image(position,chest(position).getBlockInventory().getContents()));
        return List.copyOf(images);
    }
    public static Map<Integer,Long> counts(List<Image> images,Map<Integer,Material> materials) {
        var ids=reverse(materials);var result=new HashMap<Integer,Long>();
        var positions=new HashSet<Position>();
        for(var image:images) {
            if(!positions.add(image.position()))throw new IllegalArgumentException("Duplicate physical chest");
            for(var stack:image.contents()) {
                if(stack==null || stack.getType().isAir())continue;
                var id=ids.get(stack.getType());if(id!=null)result.merge(id,(long)stack.getAmount(),Math::addExact);
            }
        }
        return Map.copyOf(result);
    }
    /** Preserve each untouched stack's components; ordinary equivalent change is placed explicitly. */
    public static List<Image> after(List<Image> before,Map<Integer,Long> remaining,Map<Integer,Material> materials) {
        var counts=counts(before,materials);var changed=new ArrayList<ItemStack[]>();
        for(var image:before)changed.add(image.contents());
        for(var entry:materials.entrySet()) {
            long target=remaining.getOrDefault(entry.getKey(),0L);
            if(target<0)throw new IllegalArgumentException("Negative food remainder");
            long remove=counts.getOrDefault(entry.getKey(),0L)-target;
            if(remove<=0)continue;
            for(var inventory:changed)for(int slot=0;slot<inventory.length && remove>0;slot++) {
                var stack=inventory[slot];if(stack==null || stack.getType()!=entry.getValue())continue;
                int amount=(int)Math.min(remove,stack.getAmount());remove-=amount;
                if(amount==stack.getAmount())inventory[slot]=null;else stack.setAmount(stack.getAmount()-amount);
            }
            if(remove!=0)throw new IllegalArgumentException("Food plan exceeds available stock");
        }
        for(var entry:remaining.entrySet()) {
            var material=materials.get(entry.getKey());
            if(material==null)throw new IllegalArgumentException("Food plan contains an unmapped item");
            long add=entry.getValue()-counts.getOrDefault(entry.getKey(),0L);if(add<=0)continue;
            var ordinary=new ItemStack(material);
            for(var inventory:changed)for(var stack:inventory) {
                if(add==0)break;
                if(stack==null || !stack.isSimilar(ordinary))continue;
                int amount=(int)Math.min(add,Math.max(0,stack.getMaxStackSize()-stack.getAmount()));
                stack.setAmount(stack.getAmount()+amount);add-=amount;
            }
            for(var inventory:changed)for(int slot=0;slot<inventory.length && add>0;slot++) {
                if(inventory[slot]!=null && !inventory[slot].getType().isAir())continue;
                int amount=(int)Math.min(add,ordinary.getMaxStackSize());inventory[slot]=new ItemStack(material,amount);add-=amount;
            }
            if(add>0)throw new IllegalArgumentException("В сундуках нет места для сдачи после обмена еды.");
        }
        var result=new ArrayList<Image>();for(int i=0;i<before.size();i++)result.add(new Image(before.get(i).position(),changed.get(i)));
        return List.copyOf(result);
    }
    /** Match old MultiInventory.addItem: fill available space and discard excess. */
    public static List<Image> addBestEffort(List<Image> images,ItemStack items) {
        long amount=items.getAmount();var inventories=new ArrayList<ItemStack[]>();
        for(var image:images)inventories.add(image.contents());
        for(var inventory:inventories)for(var stack:inventory) {
            if(amount==0)break;
            if(stack==null || !stack.isSimilar(items))continue;
            int added=(int)Math.min(amount,Math.max(0,stack.getMaxStackSize()-stack.getAmount()));
            stack.setAmount(stack.getAmount()+added);amount-=added;
        }
        for(var inventory:inventories)for(int slot=0;slot<inventory.length && amount>0;slot++) {
            if(inventory[slot]!=null && !inventory[slot].getType().isAir())continue;
            var added=items.clone();int count=(int)Math.min(amount,items.getMaxStackSize());added.setAmount(count);
            inventory[slot]=added;amount-=count;
        }
        var result=new ArrayList<Image>();for(int i=0;i<images.size();i++)result.add(new Image(images.get(i).position(),inventories.get(i)));
        return List.copyOf(result);
    }
    public static byte[] encode(List<Image> images)throws IOException {
        if(images.size()>4096)throw new IOException("Invalid consumption container count");
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(1);out.writeInt(images.size());
            for(var image:images) {
                var p=image.position();out.writeUTF(p.world());out.writeInt(p.x());out.writeInt(p.y());out.writeInt(p.z());
                var data=ItemStack.serializeItemsAsBytes(image.contents());out.writeInt(data.length);out.write(data);
            }
        }
        return bytes.toByteArray();
    }
    public static List<Image> decode(byte[] bytes)throws IOException {
        if(bytes.length>16*1024*1024)throw new IOException("Oversized consumption inventory record");
        var images=new ArrayList<Image>();var seen=new HashSet<Position>();
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=1)throw new IOException("Unknown consumption inventory version");
            int count=in.readInt();if(count<0 || count>4096)throw new IOException("Invalid consumption container count");
            for(int i=0;i<count;i++) {
                var position=new Position(in.readUTF(),in.readInt(),in.readInt(),in.readInt());
                if(!seen.add(position))throw new IOException("Duplicate consumption container");
                int length=in.readInt();if(length<1 || length>in.available())throw new IOException("Invalid consumption inventory length");
                images.add(new Image(position,ItemStack.deserializeItemsFromBytes(in.readNBytes(length))));
            }
            if(in.available()!=0)throw new IOException("Trailing consumption inventory data");
        }
        return List.copyOf(images);
    }
    /** Pending containers must be locked against player/hopper access until flush and DB completion. */
    public static Set<World> apply(List<Image> images,String receipt,NamespacedKey applied) {
        requireMainThread();var worlds=new LinkedHashSet<World>();
        // Validate every physical container before changing the first one.
        for(var image:images)chest(image.position());
        for(var image:images) {
            var chest=chest(image.position());worlds.add(chest.getWorld());
            if(receipt.equals(chest.getPersistentDataContainer().get(applied,PersistentDataType.STRING)))continue;
            chest.getSnapshotInventory().setContents(image.contents());
            chest.getPersistentDataContainer().set(applied,PersistentDataType.STRING,receipt);
            if(!chest.update(false,false))throw new IllegalStateException("Consumption chest changed before update");
        }
        return Collections.unmodifiableSet(worlds);
    }
    private static Chest chest(Position position) {
        var world=Bukkit.getWorld(position.world());if(world==null)throw new IllegalStateException("Consumption world is unavailable");
        var block=world.getBlockAt(position.x(),position.y(),position.z());
        if(!(block.getState() instanceof Chest chest) || block.getType()!=Material.CHEST)
            throw new IllegalStateException("Consumption container is missing: "+position);
        return chest;
    }
    private static Map<Material,Integer> reverse(Map<Integer,Material> materials) {
        var result=new EnumMap<Material,Integer>(Material.class);
        for(var entry:materials.entrySet())if(result.putIfAbsent(entry.getValue(),entry.getKey())!=null)
            throw new IllegalArgumentException("Ambiguous legacy food mapping");
        return result;
    }
    private static ItemStack[] copy(ItemStack[] contents) {
        var copy=contents.clone();for(int i=0;i<copy.length;i++)if(copy[i]!=null)copy[i]=copy[i].clone();return copy;
    }
    private static void requireMainThread(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Consumption inventories require the server thread");}
}
