package com.paytmmoney.seatreservation.domain;

/** Money is integer paise in a {@code long}. No floating point anywhere (ADR-021). */
public final class Money {

    private Money() {}

    /** {@code price × seats}, refusing to wrap around on overflow. */
    public static long total(long pricePaise, int seatCount) {
        try {
            return Math.multiplyExact(pricePaise, seatCount);
        } catch (ArithmeticException e) {
            throw new RequestValidationException("price_paise", "amount overflows a 64-bit paise value");
        }
    }
}
