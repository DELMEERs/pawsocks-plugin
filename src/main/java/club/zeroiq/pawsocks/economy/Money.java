package club.zeroiq.pawsocks.economy;

import club.zeroiq.pawsocks.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Деньги — «лапки». Внутри всё хранится в сотых долях (как центы), целыми числами:
 * так не бывает ошибок округления, как с дробными числами.
 */
public final class Money {

    /** Больше этого не бывает ни на счёте, ни в одной операции: 10 миллиардов лапок. */
    public static final long MAX = 1_000_000_000_000L;

    private Money() {
    }

    /**
     * «10», «10.5», «10,50», «1 000» → сотые доли. Больше двух знаков после запятой нельзя.
     * Возвращает -1, если строка не сумма или сумма не больше нуля.
     */
    public static long parse(String input) {
        if (input == null) return -1;
        String s = input.trim().replace(" ", "").replace("_", "").replace(',', '.');
        if (s.isEmpty() || s.length() > 20 || !s.matches("[0-9]+(\\.[0-9]{1,2})?")) return -1;
        try {
            BigDecimal value = new BigDecimal(s);
            if (value.scale() > 2 || value.signum() <= 0) return -1;
            BigDecimal cents = value.movePointRight(2);
            if (cents.compareTo(BigDecimal.valueOf(MAX)) > 0) return -1;
            return cents.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }

    /** 123450 → «1 234,50», 1200 → «12». */
    public static String number(long cents) {
        boolean negative = cents < 0;
        long abs = Math.abs(cents);
        long whole = abs / 100;
        long frac = abs % 100;
        StringBuilder sb = new StringBuilder(Long.toString(whole));
        for (int i = sb.length() - 3; i > 0; i -= 3) {
            sb.insert(i, ' ');
        }
        if (frac != 0) {
            sb.append(',').append(frac < 10 ? "0" : "").append(frac);
        }
        if (negative) sb.insert(0, '-');
        return sb.toString();
    }

    /** Нужная форма слова: 1 лапка, 2 лапки, 5 лапок, 1,5 лапки. */
    public static String word(long cents, String one, String few, String many) {
        if (cents % 100 != 0) return few;
        long n = Math.abs(cents / 100);
        long mod100 = n % 100;
        long mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return many;
        if (mod10 == 1) return one;
        if (mod10 >= 2 && mod10 <= 4) return few;
        return many;
    }

    /** Сумма по шаблону money.format из messages.yml, например «100 🐾 лапок». */
    public static Component format(Messages m, long cents) {
        return m.get("money.format",
                Placeholder.unparsed("amount", number(cents)),
                Placeholder.unparsed("word", word(cents, m.raw("money.one"), m.raw("money.few"), m.raw("money.many"))));
    }

    /** Подстановка суммы в текст: <name> → «100 🐾 лапок». */
    public static TagResolver tag(Messages m, String name, long cents) {
        return Placeholder.component(name, format(m, cents));
    }

    /** Доля суммы, округлённая вниз до сотых: налог, выплата и т.п. */
    public static long percent(long cents, double percent) {
        return BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR).longValue();
    }
}
