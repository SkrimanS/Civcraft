package com.avrgaming.civcraft.modern.build;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ShipBatteryPlanTest {
    LegacyBlock marker(int x,String id){return new LegacyBlock(x,8,1,68,(byte)2,List.of("/towerfire",id,"",""));}
    @Test void eachIdCreatesOneBatteryAtItsFirstMarker(){var first=marker(1,"id:2");var other=marker(5,"id:0");var template=new LegacyDefTemplate(32,16,16,List.of(first,marker(2,"id:2"),other));assertEquals(List.of(other,first),ShipBatteryPlan.groups(template));}
    @Test void absentAndInvalidIdsRejectRatherThanSilentlyDroppingShipWeapons(){assertThrows(IllegalArgumentException.class,()->ShipBatteryPlan.groups(new LegacyDefTemplate(32,16,16,List.of(marker(1,"")))));assertThrows(IllegalArgumentException.class,()->ShipBatteryPlan.groups(new LegacyDefTemplate(32,16,16,List.of(marker(1,"id:no")))));}
}
