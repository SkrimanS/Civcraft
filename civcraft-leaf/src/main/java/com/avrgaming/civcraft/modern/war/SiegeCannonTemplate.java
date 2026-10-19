package com.avrgaming.civcraft.modern.war;

import com.avrgaming.civcraft.modern.build.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** All four marker roles survive separately from the ordinary cannon blocks. */
public record SiegeCannonTemplate(int width,int height,int depth,List<LegacyBlock> blocks,Map<String,LegacyBlock> markers) {
    public SiegeCannonTemplate {
        if(width<1||height<1||depth<1)throw new IllegalArgumentException("Invalid cannon template dimensions");
        blocks=List.copyOf(blocks);markers=Map.copyOf(markers);
        if(!markers.keySet().equals(Set.of("fire","angle","power","cannon")))throw new IllegalArgumentException("Cannon template needs fire/angle/power/cannon markers");
        int facing=Byte.toUnsignedInt(markers.get("cannon").legacyData());SiegeCannonRules.direction(facing,0,0);
    }
    public static SiegeCannonTemplate load(Path path)throws IOException {
        var source=new LegacyDefTemplateLoader().load(path);var blocks=new ArrayList<LegacyBlock>();var markers=new LinkedHashMap<String,LegacyBlock>();
        for(var block:source.blocks()) {
            String first=block.signLines().isEmpty()?"":block.signLines().getFirst().trim().toLowerCase(Locale.ROOT);
            if(first.startsWith("/")) {
                String role=first.substring(1);if(!Set.of("fire","angle","power","cannon").contains(role))throw new IllegalArgumentException("Unsupported cannon marker: "+first);
                if(block.legacyId()!=63 && block.legacyId()!=68)throw new IllegalArgumentException("Cannon marker is not a sign");
                if(markers.putIfAbsent(role,block)!=null)throw new IllegalArgumentException("Duplicate cannon marker: "+role);
            }else if(block.signLines().isEmpty())blocks.add(block);
            // Original placement skips literal signs; the cannon has no literal-sign handler.
        }
        return new SiegeCannonTemplate(source.sizeX(),source.sizeY(),source.sizeZ(),blocks,markers);
    }
    public SiegeCannonRules.Vector muzzle(SiegeCannonRules.Origin origin) {
        var marker=markers.get("cannon");var direction=SiegeCannonRules.direction(Byte.toUnsignedInt(marker.legacyData()),0,0);
        return new SiegeCannonRules.Vector(origin.x()+marker.x()+direction.x(),origin.y()+marker.y(),origin.z()+marker.z()+direction.z());
    }
}
