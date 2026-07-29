package com.eurobuddha.pandadex;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Changing the LEVEL COUNT must not rewrite amounts the user typed into rungs.
 *
 * Live report: with three rungs left after clearing some with ✕, raising levels to five
 * populated nothing at all — the old path refused to do anything unless the per-side SEED size
 * fields were filled, which a user setting amounts per rung legitimately leaves empty.
 */
public class MakerRowsTest {

    private static List<MakerLadder.Level> side(String... amounts) {
        List<MakerLadder.Level> out = new ArrayList<>();
        for (String a : amounts) out.add(new MakerLadder.Level(BigDecimal.ZERO, new BigDecimal(a)));
        return out;
    }

    private static List<String> plain(List<BigDecimal> v) {
        List<String> out = new ArrayList<>();
        for (BigDecimal b : v) out.add(b.stripTrailingZeros().toPlainString());
        return out;
    }

    @Test public void raisingTheCountWorksWithNoSeedSizeAtAll() {
        // the reported bug: seed fields empty, so nothing happened
        List<BigDecimal> r = MakerLadder.applyCount(side("100", "200", "300"), 5, BigDecimal.ZERO);
        assertEquals(Arrays.asList("100", "200", "300", "300", "300", "0"), plain(r));
    }

    @Test public void newRungsInheritTheOutermostSizedRung() {
        List<BigDecimal> r = MakerLadder.applyCount(side("10", "50"), 4, BigDecimal.ZERO);
        assertEquals("50 is the outermost, so it extends",
                Arrays.asList("10", "50", "50", "50", "0", "0"), plain(r));
    }

    @Test public void handTypedAmountsAreNeverRewritten() {
        // the whole point of per-rung sizes — a count change must leave them alone
        List<BigDecimal> r = MakerLadder.applyCount(side("7", "13", "29"), 4, new BigDecimal("999"));
        assertEquals(Arrays.asList("7", "13", "29", "29", "0", "0"), plain(r));
    }

    @Test public void loweringTheCountClearsTheTailOnly() {
        List<BigDecimal> r = MakerLadder.applyCount(side("10", "20", "30", "40"), 2, BigDecimal.ZERO);
        assertEquals(Arrays.asList("10", "20", "0", "0", "0", "0"), plain(r));
    }

    @Test public void aGapInTheMiddleIsFilledFromTheOutermost() {
        // a rung cleared with ✕ leaves a hole; raising the count should not leave it unquoted
        List<BigDecimal> r = MakerLadder.applyCount(side("100", "0", "300"), 4, BigDecimal.ZERO);
        assertEquals(Arrays.asList("100", "300", "300", "300", "0", "0"), plain(r));
    }

    @Test public void anEmptySideFallsBackToTheSeedField() {
        List<BigDecimal> r = MakerLadder.applyCount(new ArrayList<>(), 3, new BigDecimal("250"));
        assertEquals(Arrays.asList("250", "250", "250", "0", "0", "0"), plain(r));
    }

    @Test public void withNothingToInheritNothingIsInvented() {
        // no rungs, no seed — leave them unquoted rather than guessing a size that spends money
        List<BigDecimal> r = MakerLadder.applyCount(new ArrayList<>(), 3, BigDecimal.ZERO);
        assertEquals(Arrays.asList("0", "0", "0", "0", "0", "0"), plain(r));
    }

    @Test public void theCountIsClampedToTheHardCap() {
        List<BigDecimal> r = MakerLadder.applyCount(side("100"), 99, BigDecimal.ZERO);
        assertEquals(Arrays.asList("100", "100", "100", "100", "100", "100"), plain(r));
    }
}
