package com.avrgaming.civcraft.modern.camp;

import com.avrgaming.civcraft.modern.town.ConsumptionContainers.Position;
import java.io.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Furnace;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Three-slot furnace images. Coordinate envelope also supports the shared producer locks. */
public final class CampFireContainers {
    private CampFireContainers() {}
    public record Image(Position position,ItemStack[] contents) {
        public Image {if(contents.length!=3)throw new IllegalArgumentException("Требуется инвентарь печи");contents=copy(contents);}
        @Override public ItemStack[] contents(){return copy(contents);}
    }
    private static ItemStack[] copy(ItemStack[] source){var out=source.clone();for(int i=0;i<out.length;i++)if(out[i]!=null)out[i]=out[i].clone();return out;}
    public static List<Image> capture(Collection<Position> positions) {
        var out=new ArrayList<Image>();for(var p:new LinkedHashSet<>(positions))out.add(new Image(p,furnace(p).getInventory().getContents()));return List.copyOf(out);
    }
    public static Optional<List<Image>> consume(List<Image> before,int amount) {
        if(amount<1)throw new IllegalArgumentException("Неверный расход угля в лагере");
        long available=0;for(var image:before)for(var item:image.contents())if(item!=null && item.getType()==Material.COAL)available+=item.getAmount();
        if(available<amount)return Optional.empty();
        var out=new ArrayList<Image>();int left=amount;
        for(var image:before){var slots=image.contents();for(int i=0;i<slots.length && left>0;i++)if(slots[i]!=null && slots[i].getType()==Material.COAL){int take=Math.min(left,slots[i].getAmount());left-=take;if(take==slots[i].getAmount())slots[i]=null;else slots[i].setAmount(slots[i].getAmount()-take);}out.add(new Image(image.position(),slots));}
        return Optional.of(List.copyOf(out));
    }
    public static byte[] encode(List<Image> images)throws IOException {
        if(images.size()>128)throw new IOException("Too many camp furnaces");var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){out.writeInt(1);out.writeInt(images.size());for(var image:images){var p=image.position();out.writeUTF(p.world());out.writeInt(p.x());out.writeInt(p.y());out.writeInt(p.z());var inventory=ItemStack.serializeItemsAsBytes(image.contents());out.writeInt(inventory.length);out.write(inventory);}}return bytes.toByteArray();
    }
    public static List<Image> decode(byte[] data)throws IOException {
        if(data==null || data.length>16*1024*1024)throw new IOException("Invalid furnace snapshot");
        var out=new ArrayList<Image>();var seen=new HashSet<Position>();
        try(var in=new DataInputStream(new ByteArrayInputStream(data))) {
            if(in.readInt()!=1)throw new IOException("Unknown furnace snapshot version");int count=in.readInt();if(count<0 || count>128)throw new IOException("Invalid furnace count");
            for(int i=0;i<count;i++){var p=new Position(in.readUTF(),in.readInt(),in.readInt(),in.readInt());if(!seen.add(p))throw new IOException("Duplicate furnace");int length=in.readInt();if(length<1 || length>in.available())throw new IOException("Invalid furnace inventory length");var items=ItemStack.deserializeItemsFromBytes(in.readNBytes(length));if(items.length!=3)throw new IOException("Invalid furnace slots");out.add(new Image(p,items));}
            if(in.available()!=0)throw new IOException("Trailing furnace snapshot data");
        }return List.copyOf(out);
    }
    public static Set<World> apply(List<Image> images,String receipt,NamespacedKey applied) {
        var worlds=new HashSet<World>();
        for(var image:images) {
            var furnace=furnace(image.position());worlds.add(furnace.getWorld());
            if(receipt.equals(furnace.getPersistentDataContainer().get(applied,PersistentDataType.STRING)))continue;
            furnace.getSnapshotInventory().setContents(image.contents());furnace.getPersistentDataContainer().set(applied,PersistentDataType.STRING,receipt);
            if(!furnace.update(false,false))throw new IllegalStateException("Camp furnace changed during recovery");
        }return worlds;
    }
    private static Furnace furnace(Position p) {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Camp furnace access requires server thread");
        var world=Bukkit.getWorld(p.world());if(world==null)throw new IllegalStateException("Camp furnace world is unavailable");
        var block=world.getBlockAt(p.x(),p.y(),p.z());if(block.getType()!=Material.FURNACE || !(block.getState() instanceof Furnace f))throw new IllegalStateException("Camp furnace is missing: "+p);return f;
    }
}
