package com.avrgaming.civcraft.modern.war;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FoundationSnapshotValidatorTest {
    final List<FoundationSnapshotValidator.Column> columns=List.of(new FoundationSnapshotValidator.Column(-17,-1),new FoundationSnapshotValidator.Column(0,16),new FoundationSnapshotValidator.Column(1,16),new FoundationSnapshotValidator.Column(2,16));
    @Test void holesBelowFoundationRejectAtTheFirstInvalidLayer(){var result=FoundationSnapshotValidator.scan(columns,79,-64,c->c.y()==77?0:1);assertFalse(result.valid());assertEquals(77,result.y());assertEquals(3,result.depth());}
    @Test void firstTenLayersNeedEightyPercentAndDeeperLayersNeedTwentyFour(){assertFalse(FoundationSnapshotValidator.scan(columns,79,0,c->c.y()==70?(c.x()==-17?1:0):1).valid());assertTrue(FoundationSnapshotValidator.scan(columns,79,0,c->c.y()==69?(c.x()==-17?1:0):1).valid());}
    @Test void scansModernNegativeHeightAndCountsMaterialStrength(){var result=FoundationSnapshotValidator.scan(columns,2,-64,c->c.y()==-10?0:4);assertFalse(result.valid());assertEquals(-10,result.y());assertTrue(FoundationSnapshotValidator.scan(columns,2,-64,c->c.x()==-17?4:0).valid());}
    @Test void emptyTemplateFootprintAndWorldFloorDoNotReject(){assertTrue(FoundationSnapshotValidator.scan(List.of(),79,-64,c->{throw new AssertionError();}).valid());assertTrue(FoundationSnapshotValidator.scan(columns,-64,-64,c->{throw new AssertionError();}).valid());}
}
