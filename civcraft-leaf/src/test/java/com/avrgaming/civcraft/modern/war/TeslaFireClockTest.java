package com.avrgaming.civcraft.modern.war;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TeslaFireClockTest {
    @Test void defaultDelayFiresOnSeventhEligiblePulseAndRepeats(){var clock=new TeslaFireClock(6);for(int round=0;round<2;round++){for(int pulse=0;pulse<6;pulse++)assertFalse(clock.ready(1));assertTrue(clock.ready(1));}}
    @Test void towersKeepIndependentCountersAndRestartStartsFresh(){var clock=new TeslaFireClock(1);assertFalse(clock.ready(1));assertFalse(clock.ready(2));assertTrue(clock.ready(1));assertTrue(clock.ready(2));assertFalse(new TeslaFireClock(1).ready(1));assertTrue(new TeslaFireClock(0).ready(1));assertThrows(IllegalArgumentException.class,()->new TeslaFireClock(-1));}
}
