package com.avrgaming.civcraft.modern.build;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StaticAttributesTest {
    @Test void readsOriginalStaticComponentsAndKeepsRatesSeparate() {
        var attributes=StaticAttributes.parse(List.of(
                Map.of("name","AttributeStatic","attribute","BEAKERS","value","100.0"),
                Map.of("name","AttributeStatic","attribute","HAMMERS","value","50.0"),
                Map.of("name","AttributeStatic","attribute","CULTURE","value","25"),
                Map.of("name","AttributeRate","attribute","BEAKERS","value","0.5")));
        assertEquals(100,attributes.get("BEAKERS"));assertEquals(50,attributes.get("HAMMERS"));assertEquals(25,attributes.get("CULTURE"));
    }
    @Test void unhappinessReductionsAndEducationUseOriginalCaps() {
        var attributes=StaticAttributes.parse(List.of(
                Map.of("name","AttributeStatic","attribute","HAPPINESS","value","2"),
                Map.of("name","AttributeStatic","attribute","UNHAPPINESS","value","-2"),
                Map.of("name","AttributeStatic","attribute","BEAKERBOOST","value","0.01")));
        assertEquals(4,attributes.happiness());assertEquals(0.05,attributes.education("s_university",100));
        assertEquals(0.1,attributes.education("s_school",100));assertEquals(0.01,attributes.education("s_research_lab",1));
        assertEquals(0,attributes.education("s_library",100));
    }
    @Test void rejectsNonFiniteProductionAndAcceptsEmptyComponents() {
        assertEquals(0,StaticAttributes.parse(null).get("BEAKERS"));
        assertThrows(IllegalArgumentException.class,()->StaticAttributes.parse(List.of(Map.of("name","AttributeStatic","attribute","BEAKERS","value","NaN"))));
    }
}
