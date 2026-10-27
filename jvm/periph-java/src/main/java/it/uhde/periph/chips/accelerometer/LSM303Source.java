package it.uhde.periph.chips.accelerometer;

/** Interrupt source constants for the LSM303 Full driver. */
public final class LSM303Source {
    public static final int SOURCE_DATA_READY = 0x0001;
    public static final int SOURCE_WATERMARK = 0x0002;
    public static final int SOURCE_OVERRUN = 0x0004;
    public static final int SOURCE_IA1 = 0x0008;
    public static final int SOURCE_IA2 = 0x0010;
    public static final int SOURCE_CLICK = 0x0020;
    public static final int SOURCE_BOOT = 0x0040;
    public static final int SOURCE_MAG_DATA_READY = 0x0080;

    private LSM303Source() {
    }
}
