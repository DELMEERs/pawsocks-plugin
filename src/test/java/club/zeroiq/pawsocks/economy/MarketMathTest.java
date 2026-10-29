package club.zeroiq.pawsocks.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketMathTest {

    private static final double T = 500, FLOOR = 0.25;

    @Test
    void sellingInPartsPaysTheSameAsAtOnce() {
        double once = MarketMath.payout(0, 1000, T, FLOOR);
        double p = 0, parts = 0;
        for (int i = 0; i < 1000; i++) {
            parts += MarketMath.payout(p, 1, T, FLOOR);
            p += 1;
        }
        assertEquals(once, parts, 1e-6);
    }

    @Test
    void neverPaysMoreThanBaseOrLessThanFloor() {
        for (double p : new double[]{0, 100, 1000, 10_000}) {
            double paid = MarketMath.payout(p, 100, T, FLOOR);
            assertTrue(paid <= 100 + 1e-9);
            assertTrue(paid >= 100 * FLOOR - 1e-9);
        }
    }

    @Test
    void multiplierHalvesAtSaturation() {
        assertEquals(1, MarketMath.multiplier(0, T, FLOOR), 1e-9);
        assertEquals(0.5, MarketMath.multiplier(T, T, FLOOR), 1e-9);
        assertEquals(FLOOR, MarketMath.multiplier(100 * T, T, FLOOR), 1e-9);
    }

    @Test
    void cooldown() {
        long h = 3_600_000L;
        assertEquals(200, MarketMath.decay(400, 12 * h, 12 * h), 1e-6);
        assertEquals(400, MarketMath.decay(400, 0, 12 * h), 1e-6);
    }
}
