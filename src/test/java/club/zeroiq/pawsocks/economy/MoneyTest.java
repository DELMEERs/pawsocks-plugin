package club.zeroiq.pawsocks.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoneyTest {

    @Test
    void parse() {
        assertEquals(1000, Money.parse("10"));
        assertEquals(1050, Money.parse("10.5"));
        assertEquals(1050, Money.parse("10,50"));
        assertEquals(1, Money.parse("0.01"));
        assertEquals(100000, Money.parse("1 000"));
        assertEquals(-1, Money.parse("0"));
        assertEquals(-1, Money.parse("-5"));
        assertEquals(-1, Money.parse("1.001"));
        assertEquals(-1, Money.parse("abc"));
        assertEquals(-1, Money.parse("NaN"));
        assertEquals(-1, Money.parse("1e3"));
        assertEquals(-1, Money.parse("999999999999999"));
        assertEquals(-1, Money.parse(""));
    }

    @Test
    void number() {
        assertEquals("12", Money.number(1200));
        assertEquals("1 234,50", Money.number(123450));
        assertEquals("0,05", Money.number(5));
        assertEquals("1 000 000", Money.number(100_000_000));
    }

    @Test
    void words() {
        String[] w = {"лапка", "лапки", "лапок"};
        assertEquals("лапка", Money.word(100, w[0], w[1], w[2]));
        assertEquals("лапки", Money.word(300, w[0], w[1], w[2]));
        assertEquals("лапок", Money.word(500, w[0], w[1], w[2]));
        assertEquals("лапок", Money.word(1100, w[0], w[1], w[2]));
        assertEquals("лапок", Money.word(1200, w[0], w[1], w[2]));
        assertEquals("лапка", Money.word(2100, w[0], w[1], w[2]));
        assertEquals("лапки", Money.word(2200, w[0], w[1], w[2]));
        assertEquals("лапок", Money.word(10000, w[0], w[1], w[2]));
        assertEquals("лапок", Money.word(0, w[0], w[1], w[2]));
        assertEquals("лапки", Money.word(150, w[0], w[1], w[2]));
    }

    @Test
    void percent() {
        assertEquals(5, Money.percent(100, 5));
        assertEquals(0, Money.percent(19, 5));
        assertEquals(50, Money.percent(1000, 5));
    }
}
