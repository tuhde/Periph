package it.uhde.periph.chips.accelerometer

/** Interrupt source constants for the LSM303 Full driver. */
class LSM303Source {
    static final int SOURCE_DATA_READY = 0x0001
    static final int SOURCE_WATERMARK = 0x0002
    static final int SOURCE_OVERRUN = 0x0004
    static final int SOURCE_IA1 = 0x0008
    static final int SOURCE_IA2 = 0x0010
    static final int SOURCE_CLICK = 0x0020
    static final int SOURCE_BOOT = 0x0040
    static final int SOURCE_MAG_DATA_READY = 0x0080
}
