///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS7828Full

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, ADS7828Full.DEFAULT_ADDRESS)              // open I²C bus, device address, (bus, address=0x48) → I2CConnection
try {
    def adc = new ADS7828Full(connection)                                                           // construct driver, (connection, vref=null → internal 2.5 V) → ADS7828Full
    adc.init()                                                                                      // Enable internal reference, () → void
    final double divider = 2.0d   // inputs sit behind 2:1 dividers (rails up to 5 V)
    def channels = [0, 1, 2, 3, 4, 5, 6, 7] as int[]

    // --- Eight-channel supply monitor ---
    // Scan CH0–CH7 with the internal 2.5 V reference, print raw codes and rail
    // voltages (after the divider ratio) and keep a running minimum/maximum per channel.
    def vmin = [99.0d] * 8
    def vmax = [0.0d] * 8
    for (int n = 0; n < 5; n++) {
        int[] raws = adc.readScan(channels)                                                         // Scan channels, (channels int[]) → int[]
        for (int ch = 0; ch < 8; ch++) {
            double volts = raws[ch] * adc.lsb() * divider
            vmin[ch] = Math.min((double) vmin[ch], volts)
            vmax[ch] = Math.max((double) vmax[ch], volts)
            println("ch${ch} raw=${raws[ch]} ${volts} V")
        }
        Thread.sleep(1000)
    }
    println("min ${vmin} max ${vmax}")

    // --- Read a bridge sensor on CH0/CH1 ---
    // The signed differential read does two conversions (normal and inverted
    // polarity) so the sign of the bridge output is preserved.
    double mv = adc.readSignedDifferential(0) * 1000.0d                                             // Read pair 0 signed, (pair 0–3) → double V
    println("bridge ${mv} mV")

    // --- Second scan against an external 5.0 V reference ---
    // Switching to a 5 V external reference gives full-scale range up to VDD.
    adc.setReference(5.0d)                                                                          // Use external reference, (vref Double V; null = internal) → void
    def ext = adc.readScan(channels)                                                                // Scan channels, (channels int[]) → int[]
    println("ext 5 V scan ${ext}")

    // --- Finish in full power-down ---
    adc.powerDown()                                                                                 // Power down completely, () → void
} finally {
    connection.close()
}
