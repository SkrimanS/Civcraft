package com.avrgaming.civcraft.modern.trade;

import com.avrgaming.civcraft.modern.build.LegacyBlock;
import java.util.*;

public record GoodieFrameMarker(int slot,int x,int y,int z,String facing,boolean source) {
    public static Optional<GoodieFrameMarker> parse(String type,LegacyBlock block) {
        var lines=block.signLines();if(lines.isEmpty())return Optional.empty();
        if(TradeDepositStore.outpost(type)&&lines.getFirst().trim().equalsIgnoreCase("/tradeoutpost"))
            return Optional.of(new GoodieFrameMarker(-1,block.x()+1,block.y()+1,block.z(),"EAST",true));
        if(!Set.of("s_townhall","s_capitol").contains(type)||!lines.getFirst().trim().equalsIgnoreCase("/itemframe"))return Optional.empty();
        String direction=switch(Byte.toUnsignedInt(block.legacyData())){case 2->"NORTH";case 3->"SOUTH";case 4->"WEST";case 5->"EAST";default->null;};
        if(direction==null)return Optional.empty();
        for(String line:lines.subList(1,lines.size()))if(line.trim().startsWith("id:"))try {
            int slot=Integer.parseInt(line.trim().substring(3).trim());
            if(slot>=0&&slot<8)return Optional.of(new GoodieFrameMarker(slot,block.x(),block.y(),block.z(),direction,false));
        }catch(NumberFormatException ignored){return Optional.empty();}
        return Optional.empty();
    }
}
