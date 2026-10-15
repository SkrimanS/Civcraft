package com.avrgaming.civcraft.modern.trade;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankExchangeTest {
    final GoodieEffects effects=new GoodieEffects(new TradeGoodsCatalog(new File("src/main/resources/civcraft/data/goods.yml")),new File("src/main/resources/civcraft/data/buffs.yml"));
    @Test void originalLevelsRetainTheirExchangeRates() {
        double[] expected={.4,.5,.6,.7,.8,.9,1,1.2,1.5,2};
        for(int i=0;i<expected.length;i++)assertEquals(expected[i],BankExchange.rate(i+1,0));
    }
    @Test void framedSilverAndGemsStackBeforeTruncationOfTheWholeStack() {
        double one=effects.calculate(List.of("good_silver")).value("buff_barter");
        assertEquals(.42,BankExchange.rate(1,one),1e-9);
        assertEquals(537,BankExchange.gross(20,64,BankExchange.rate(1,one)));
        assertEquals(8,BankExchange.gross(20,1,BankExchange.rate(1,one)));
        assertEquals(563,BankExchange.gross(20,64,BankExchange.rate(1,effects.calculate(List.of("good_silver","good_gems")).value("buff_barter"))));
        assertEquals(512,BankExchange.gross(20,64,BankExchange.rate(1,GoodieEffects.Values.NONE.value("buff_barter"))));
    }
    @Test void invalidRatesAndOverflowCannotCreateWalletCredit() {
        assertThrows(IllegalArgumentException.class,()->BankExchange.rate(0,0));
        assertThrows(IllegalArgumentException.class,()->BankExchange.rate(11,0));
        assertThrows(IllegalArgumentException.class,()->BankExchange.rate(1,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->BankExchange.gross(0x1p63,1,1));
        assertThrows(IllegalArgumentException.class,()->BankExchange.gross(20,0,1));
    }
}
