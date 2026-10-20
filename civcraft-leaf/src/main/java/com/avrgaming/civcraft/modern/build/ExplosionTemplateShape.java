package com.avrgaming.civcraft.modern.build;

import java.util.BitSet;

/** Compact source template membership: AIR and slash-command signs are not StructureBlocks. */
public final class ExplosionTemplateShape {
    private final int width,height,depth;
    private final BitSet solids,commands;
    private ExplosionTemplateShape(int width,int height,int depth){this.width=width;this.height=height;this.depth=depth;solids=new BitSet();commands=new BitSet();}
    private int index(int x,int y,int z){return (y*depth+z)*width+x;}
    public int width(){return width;}
    public int height(){return height;}
    public int depth(){return depth;}
    public boolean solid(int x,int y,int z){return solids.get(index(x,y,z));}
    public boolean command(int x,int y,int z){return commands.get(index(x,y,z));}
    public static ExplosionTemplateShape legacy(LegacyDefTemplate template) {
        var shape=new ExplosionTemplateShape(template.sizeX(),template.sizeY(),template.sizeZ());
        for(var block:template.blocks())if(!block.isAir()) {
            int index=shape.index(block.x(),block.y(),block.z());
            boolean command=(block.legacyId()==63 || block.legacyId()==68) && !block.signLines().isEmpty() && block.signLines().getFirst().startsWith("/");
            if(command)shape.commands.set(index);else shape.solids.set(index);
        }
        return shape;
    }
    public static ExplosionTemplateShape schematic(WorldEditSchematicTemplate template) {
        var shape=new ExplosionTemplateShape(template.sizeX(),template.sizeY(),template.sizeZ());
        for(var block:template.blocks())if(!block.air())shape.solids.set(shape.index(block.x(),block.y(),block.z()));
        return shape;
    }
}
