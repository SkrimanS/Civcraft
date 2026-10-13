package com.avrgaming.civcraft.modern.items;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LegacyRecipeTest {
    private final LegacyRecipe.Ingredient lumber=new LegacyRecipe.Ingredient("mat_milled_lumber",Set.of("OAK_STAIRS"));
    private final LegacyRecipe.Ingredient hat=new LegacyRecipe.Ingredient("mat_chieftains_headdress",Set.of("CHAINMAIL_HELMET"));
    private final LegacyRecipe.Ingredient coal=new LegacyRecipe.Ingredient(null,Set.of("COAL_BLOCK"));
    private final LegacyRecipe camp=new LegacyRecipe("mat_found_camp",List.of(" h ","lll","ccc"),Map.of('h',hat,'l',lumber,'c',coal),List.of());
    private LegacyRecipe.Token nativeItem(String id,String material){return new LegacyRecipe.Token(id,material);}
    private List<LegacyRecipe.Token> campMatrix(){return new ArrayList<>(Arrays.asList(null,nativeItem("mat_chieftains_headdress","CHAINMAIL_HELMET"),null,
            nativeItem("mat_milled_lumber","OAK_STAIRS"),nativeItem("mat_milled_lumber","OAK_STAIRS"),nativeItem("mat_milled_lumber","OAK_STAIRS"),
            nativeItem(null,"COAL_BLOCK"),nativeItem(null,"COAL_BLOCK"),nativeItem(null,"COAL_BLOCK")));}
    @Test void originalCampMatchesWithNullEmptySlots(){assertTrue(camp.matches(campMatrix(),3));}
    @Test void vanillaLookalikeCannotReplaceCustomMaterial(){List<LegacyRecipe.Token> grid=campMatrix();grid.set(3,nativeItem(null,"OAK_STAIRS"));assertFalse(camp.matches(grid,3));}
    @Test void sameIconWithWrongCivcraftIdDoesNotMatch(){List<LegacyRecipe.Token> grid=campMatrix();grid.set(1,nativeItem("mat_other_helmet","CHAINMAIL_HELMET"));assertFalse(camp.matches(grid,3));}
    @Test void emptySpaceCannotContainAnExtraItem(){List<LegacyRecipe.Token> grid=campMatrix();grid.set(0,nativeItem(null,"DIRT"));assertFalse(camp.matches(grid,3));}
    @Test void wildcardLogsStillRequireNineSlots(){LegacyRecipe.Ingredient logs=new LegacyRecipe.Ingredient(null,Set.of("OAK_LOG","BIRCH_LOG"));LegacyRecipe recipe=new LegacyRecipe("wood",List.of(),Map.of(),Collections.nCopies(9,logs));assertTrue(recipe.matches(Collections.nCopies(9,nativeItem(null,"BIRCH_LOG")),3));assertFalse(recipe.matches(Arrays.asList(nativeItem(null,"OAK_LOG"),null,null,null,null,null,null,null,null),3));}
    @Test void customLogCannotBeSpentAsVanillaLog(){LegacyRecipe.Ingredient logs=new LegacyRecipe.Ingredient(null,Set.of("OAK_LOG"));assertFalse(logs.matches(nativeItem("mat_refined_wood","OAK_LOG")));}
    @Test void shapedRecipeWorksInSmallerPlayerGrid(){LegacyRecipe recipe=new LegacyRecipe("small",List.of("a"),Map.of('a',coal),List.of());assertTrue(recipe.matches(Arrays.asList(null,null,null,nativeItem(null,"COAL_BLOCK")),2));}
    @Test void shapelessOverlappingChoicesAreAssignedWithoutGreedyFailure(){LegacyRecipe.Ingredient broad=new LegacyRecipe.Ingredient(null,Set.of("OAK_LOG","BIRCH_LOG"));LegacyRecipe.Ingredient exact=new LegacyRecipe.Ingredient(null,Set.of("OAK_LOG"));LegacyRecipe recipe=new LegacyRecipe("mixed",List.of(),Map.of(),List.of(broad,exact));assertTrue(recipe.matches(Arrays.asList(nativeItem(null,"OAK_LOG"),nativeItem(null,"BIRCH_LOG"),null,null),2));}
}
