package it.uhde.periph.chips.accelerometer

import groovy.transform.CompileStatic

/**
 * LIS3DH interrupt source constants.
 *
 * The value of a source is also the bit position it occupies in the combined
 * status returned by {@code Lis3dhFull.pollInterrupt()}. The constants are
 * the argument of {@code Lis3dhFull.enableInterrupt()} and
 * {@code Lis3dhFull.disableInterrupt()}.
 *
 * {@code SOURCE_ACTIVITY} and {@code SOURCE_BOOT} are pin-only: the chip has
 * no register flag for them, so {@code pollInterrupt()} never reports them.
 */
@CompileStatic
class Lis3dhSource {

    /** A new X/Y/Z sample set is available ({@code STATUS_REG.ZYXDA}) — INT1 only. */
    static final int SOURCE_DATA_READY = 0x0001

    /** A new auxiliary ADC sample is available ({@code STATUS_REG_AUX.321DA}) — INT1 only. */
    static final int SOURCE_AUX_DATA_READY = 0x0002

    /** The FIFO content exceeds the watermark ({@code FIFO_SRC_REG.WTM}) — INT1 only. */
    static final int SOURCE_WATERMARK = 0x0004

    /** The FIFO is full and overwrites the oldest sample ({@code FIFO_SRC_REG.OVRN_FIFO}) — INT1 only. */
    static final int SOURCE_OVERRUN = 0x0008

    /** Inertial interrupt generator 1 fired ({@code INT1_SRC.IA}) — INT1 or INT2. */
    static final int SOURCE_IA1 = 0x0010

    /** Inertial interrupt generator 2 fired ({@code INT2_SRC.IA}) — INT1 or INT2. */
    static final int SOURCE_IA2 = 0x0020

    /** A single or double click was detected ({@code CLICK_SRC.IA}) — INT1 or INT2. */
    static final int SOURCE_CLICK = 0x0040

    /** The sleep-to-wake / return-to-sleep activity function fired — INT2 only. */
    static final int SOURCE_ACTIVITY = 0x0080

    /** The boot procedure is running — INT2 only. */
    static final int SOURCE_BOOT = 0x0100

    private Lis3dhSource() {
    }
}
