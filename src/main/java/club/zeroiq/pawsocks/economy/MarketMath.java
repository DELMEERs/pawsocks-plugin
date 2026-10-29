package club.zeroiq.pawsocks.economy;

/**
 * Насыщение рынка. Сервер скупает вещи не бесконечно: чем больше какой-то вещи продали
 * за последнее время, тем дешевле он её берёт. Давление (сколько лапок по базовой цене
 * продали) со временем остывает вдвое за halfLife.
 *
 * множитель цены = max(1 / (1 + давление / насыщение), минимум)
 *
 * Выплата за партию считается как интеграл множителя по проданной стоимости, поэтому
 * без разницы, продать 1000 железа разом или по одному — денег выйдет одинаково,
 * и обойти насыщение мелкими продажами нельзя.
 */
public final class MarketMath {

    private MarketMath() {
    }

    /** Давление после остывания за elapsedMillis. */
    public static double decay(double pressure, long elapsedMillis, long halfLifeMillis) {
        if (pressure <= 0 || elapsedMillis <= 0 || halfLifeMillis <= 0) return Math.max(0, pressure);
        return pressure * Math.pow(0.5, (double) elapsedMillis / halfLifeMillis);
    }

    /** Текущий множитель цены (0..1]. */
    public static double multiplier(double pressure, double saturation, double floor) {
        if (saturation <= 0) return 1;
        return Math.max(1 / (1 + Math.max(0, pressure) / saturation), floor);
    }

    /**
     * Сколько заплатить за партию стоимостью value (в лапках по базовой цене)
     * при текущем давлении pressure. Результат в лапках.
     */
    public static double payout(double pressure, double value, double saturation, double floor) {
        if (value <= 0) return 0;
        if (saturation <= 0) return value;
        double a = Math.max(0, pressure);
        double b = a + value;
        // после этой точки множитель упирается в минимум
        double cross = floor > 0 && floor < 1 ? saturation * (1 / floor - 1) : Double.POSITIVE_INFINITY;
        double total = 0;
        if (a < cross) {
            double end = Math.min(b, cross);
            total += saturation * Math.log((saturation + end) / (saturation + a));
        }
        if (b > cross) {
            total += floor * (b - Math.max(a, cross));
        }
        return total;
    }
}
