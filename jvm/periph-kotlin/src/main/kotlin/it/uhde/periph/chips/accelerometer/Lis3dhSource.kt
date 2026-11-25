package it.uhde.periph.chips.accelerometer

/**
 * LIS3DH interrupt source constants.
 *
 * The value of a source is also the bit position it occupies in the combined
 * status returned by [Lis3dhFull.pollInterrupt]. The constants are the
 * argument of [Lis3dhFull.enableInterrupt] and [Lis3dhFull.disableInterrupt].
 *
 * [SOURCE_ACTIVITY] and [SOURCE_BOOT] are pin-only: the chip has no register
 * flag for them, so [Lis3dhFull.pollInterrupt] never reports them.
 */
object Lis3dhSource {

    /** A new X/Y/Z sample set is available (`STATUS_REG.ZYXDA`) — INT1 only. */
    const val SOURCE_DATA_READY = 0x0001

    /** A new auxiliary ADC sample is available (`STATUS_REG_AUX.321DA`) — INT1 only. */
    const val SOURCE_AUX_DATA_READY = 0x0002

    /** The FIFO content exceeds the watermark (`FIFO_SRC_REG.WTM`) — INT1 only. */
    const val SOURCE_WATERMARK = 0x0004

    /** The FIFO is full and overwrites the oldest sample (`FIFO_SRC_REG.OVRN_FIFO`) — INT1 only. */
    const val SOURCE_OVERRUN = 0x0008

    /** Inertial interrupt generator 1 fired (`INT1_SRC.IA`) — INT1 or INT2. */
    const val SOURCE_IA1 = 0x0010

    /** Inertial interrupt generator 2 fired (`INT2_SRC.IA`) — INT1 or INT2. */
    const val SOURCE_IA2 = 0x0020

    /** A single or double click was detected (`CLICK_SRC.IA`) — INT1 or INT2. */
    const val SOURCE_CLICK = 0x0040

    /** The sleep-to-wake / return-to-sleep activity function fired — INT2 only. */
    const val SOURCE_ACTIVITY = 0x0080

    /** The boot procedure is running — INT2 only. */
    const val SOURCE_BOOT = 0x0100
}
