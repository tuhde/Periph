package it.uhde.periph.connection;

/** Sign-extension helper for register values — pure integer math, no bus dependency. */
public final class Register {
    private Register() {}

    /**
     * Interpret the low {@code bits} bits of {@code value} as two's-complement signed.
     *
     * @param value unsigned raw register value
     * @param bits  width of the value in bits (e.g. 16, 24)
     * @return the signed interpretation of the low {@code bits} bits of {@code value}
     */
    public static int toSigned(int value, int bits) {
        int signBit = 1 << (bits - 1);
        return (value & (signBit - 1)) - (value & signBit);
    }
}
