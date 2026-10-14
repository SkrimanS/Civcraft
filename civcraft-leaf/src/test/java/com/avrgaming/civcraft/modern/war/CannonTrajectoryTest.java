package com.avrgaming.civcraft.modern.war;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CannonTrajectoryTest {
    @Test void shellStepsSixBlocksTowardCapturedTargetAndFinishesWithoutOvershootLoop(){var origin=new CannonTrajectory.Point(0,0,0);var target=new CannonTrajectory.Point(15,0,0);var first=CannonTrajectory.advance(origin,target,6);assertFalse(first.arrived());assertEquals(6,first.position().x());var second=CannonTrajectory.advance(first.position(),target,6);assertFalse(second.arrived());var third=CannonTrajectory.advance(second.position(),target,6);assertTrue(third.arrived());assertEquals(target,third.position());}
    @Test void zeroDistanceAndShortFlightTerminateAndInvalidSpeedRejects(){var point=new CannonTrajectory.Point(1,2,3);assertTrue(CannonTrajectory.advance(point,point,6).arrived());assertTrue(CannonTrajectory.advance(point,new CannonTrajectory.Point(4,2,3),6).arrived());assertThrows(IllegalArgumentException.class,()->CannonTrajectory.advance(point,point,0));}
    @Test void splashUsesFlooredBlockCoordinatesAndSquaredStrictRadius(){var target=new CannonTrajectory.Point(.9,.9,.9);assertTrue(CannonTrajectory.splash(new CannonTrajectory.Point(5.9,.9,.9),target,30));assertFalse(CannonTrajectory.splash(new CannonTrajectory.Point(6,.9,.9),target,30));assertFalse(CannonTrajectory.splash(new CannonTrajectory.Point(1,2,5),target,30));assertTrue(CannonTrajectory.splash(new CannonTrajectory.Point(-.1,0,0),target,30));}
    @Test void cannonCounterFiresOnFifthEligiblePulse(){var counter=new TeslaFireClock(4);for(int i=0;i<4;i++)assertFalse(counter.ready(1));assertTrue(counter.ready(1));}
}
