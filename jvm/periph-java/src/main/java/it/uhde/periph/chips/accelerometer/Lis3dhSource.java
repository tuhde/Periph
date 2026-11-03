package it.uhde.periph.chips.accelerometer;

/**
 * LIS3DH interrupt source constants.
 *
 * <p>The value of a source is also the bit position it occupies in the
 * combined status returned by {@link Lis3dhFull#pollInterrupt()}. The
 * constants are the argument of
 * {@link Lis3dhFull#enableInterrupt(int, int)} and
 * {@link Lis3dhFull#disableInterrupt(int, int)}.
 *
 * <p>{@link #SOURCE_ACTIVITY} and {@link #SOURCE_BOOT} are pin-only: the chip
 * has no register flag for them, so {@code pollInterrupt()} never reports
 * them.
 */
public final class Lis3dhSource {

    private Lis3dhSource() {
    }

    /** A new X/Y/Z sample set is available ({@code STATUS_REG.ZYXDA}) — INT1 only. */
    public static final int SOURCE_DATA_READY = 0x0001;

    /** A new auxiliary ADC sample is available ({@code STATUS_REG_AUX.321DA}) — INT1 only. */
    public static final int SOURCE_AUX_DATA_READY = 0x0002;

    /** The FIFO content exceeds the watermark ({@code FIFO_SRC_REG.WTM}) — INT1 only. */
    public static final int SOURCE_WATERMARK = 0x0004;

    /** The FIFO is full and overwrites the oldest sample ({@code FIFO_SRC_REG.OVRN_FIFO}) — INT1 only. */
    public static final int SOURCE_OVERRUN = 0x0008;

    /** Inertial interrupt generator 1 fired ({@code INT1_SRC.IA}) — INT1 or INT2. */
    public static final int SOURCE_IA1 = 0x0010;

    /** Inertial interrupt generator 2 fired ({@code INT2_SRC.IA}) — INT1 or INT2. */
    public static final int SOURCE_IA2 = 0x0020;

    /** A single or double click was detected ({@code CLICK_SRC.IA}) — INT1 or INT2. */
    public static final int SOURCE_CLICK = 0x0040;

    /** The sleep-to-wake / return-to-sleep activity function fired — INT2 only. */
    public static final int SOURCE_ACTIVITY = 0x0080;

    /** The boot procedure is running — INT2 only. */
    public static final int SOURCE_BOOT = 0x0100;
}
