package com.avrgaming.civcraft.modern.build;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Chest;

public final class StructureChestBlocks {
    private StructureChestBlocks() {}
    public static void place(Block block,byte direction) {
        block.setType(Material.CHEST,false);
        var data=(Chest)block.getBlockData();data.setFacing(BlockFace.valueOf(ChestMarker.facing(direction).toUpperCase(Locale.ROOT)));
        block.setBlockData(data,false);
    }
    private record Key(String world,int x,int y,int z) {}
    public static void pair(Collection<BlockPlacement> placements) {
        var points=new HashMap<Key,BlockPlacement>();
        for(var p:placements)if(p.material().equals("CHEST") && ChestMarker.parse(p.signLines()).isPresent())points.put(new Key(p.world(),p.x(),p.y(),p.z()),p);
        var paired=new HashSet<Key>();
        for(var entry:points.entrySet()) {
            var key=entry.getKey();if(paired.contains(key))continue;var world=Bukkit.getWorld(key.world());if(world==null)continue;
            var block=world.getBlockAt(key.x(),key.y(),key.z());if(!(block.getBlockData() instanceof Chest first))continue;
            var clockwise=switch(first.getFacing()){case NORTH->BlockFace.EAST;case EAST->BlockFace.SOUTH;case SOUTH->BlockFace.WEST;case WEST->BlockFace.NORTH;default->BlockFace.SELF;};
            for(var direction:List.of(clockwise,clockwise.getOppositeFace())) {
                var partnerKey=new Key(key.world(),key.x()+direction.getModX(),key.y(),key.z()+direction.getModZ());
                var partner=points.get(partnerKey);if(partner==null || paired.contains(partnerKey))continue;
                if(ChestMarker.parse(partner.signLines()).orElseThrow().id()!=ChestMarker.parse(entry.getValue().signLines()).orElseThrow().id())continue;
                var other=world.getBlockAt(partnerKey.x(),partnerKey.y(),partnerKey.z());
                if(!(other.getBlockData() instanceof Chest second) || first.getFacing()!=second.getFacing())continue;
                updateType(block,first,direction==clockwise?Chest.Type.LEFT:Chest.Type.RIGHT);
                updateType(other,second,direction==clockwise?Chest.Type.RIGHT:Chest.Type.LEFT);
                paired.add(key);paired.add(partnerKey);break;
            }
        }
    }
    private static void updateType(Block block,Chest data,Chest.Type type) {
        if(data.getType()==type)return;
        var state=(org.bukkit.block.Chest)block.getState();var contents=state.getBlockInventory().getContents();
        for(int i=0;i<contents.length;i++)if(contents[i]!=null)contents[i]=contents[i].clone();
        data.setType(type);block.setBlockData(data,false);
        ((org.bukkit.block.Chest)block.getState()).getBlockInventory().setContents(contents);
    }
}
