package com.avrgaming.civcraft.modern.war;

import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;

/** Paper's actual block snapshot includes inventories and vanilla block-entity state. */
public final class BlockEntitySnapshot {
    private BlockEntitySnapshot() {}
    public static String read(BlockState state) {
        if(!(state instanceof TileState))return null;
        try {
            Object tag=state.getClass().getMethod("getSnapshotNBT").invoke(state);
            String encoded=tag.toString();if(!encoded.startsWith("{")||!encoded.endsWith("}"))throw new IllegalStateException("Invalid block entity snapshot");
            return encoded;
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Не удалось сохранить полные данные блока "+state.getType()+".",failure);}
    }
    public static void restore(BlockState state,String encoded) {
        if(encoded==null)return;
        if(!(state instanceof TileState))throw new IllegalStateException("Snapshot target is not a block entity");
        try {
            Class<?> parser=Class.forName("net.minecraft.nbt.TagParser"),compound=Class.forName("net.minecraft.nbt.CompoundTag");
            Object tag=parser.getMethod("parseCompoundFully",String.class).invoke(null,encoded);
            state.getClass().getMethod("loadData",compound).invoke(state,tag);
            if(!state.update(true,false))throw new IllegalStateException("Block entity snapshot update rejected");
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Не удалось восстановить полные данные блока "+state.getType()+".",failure);}
    }
}
