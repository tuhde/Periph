package it.uhde.periph.chips.accelerometer

/** Interrupt source constants for the LSM303 Full driver. */
object LSM303Source {
    const val SOURCE_DATA_READY = 0x0001
    const val SOURCE_WATERMARK = 0x0002
    const val SOURCE_OVERRUN = 0x0004
    const val SOURCE_IA1 = 0x0008
    const val SOURCE_IA2 = 0x0010
    const val SOURCE_CLICK = 0x0020
    const val SOURCE_BOOT = 0x0040
    const val SOURCE_MAG_DATA_READY = 0x0080
}
