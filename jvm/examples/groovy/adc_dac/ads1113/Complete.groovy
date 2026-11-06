///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.2
//DEPS it.uhde:periph-groovy:1.3.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS1113Full

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, ADS1113Full.DEFAULT_ADDRESS)                    // open I²C bus, device address, (bus, address=0x48) → I2CConnection
try {
        def adc = new ADS1113Full(connection)                                               // construct driver, (connection) → ADS1113Full
                                                                                            // no bus traffic; Config word is rebuilt per conversion
        def raw = adc.readRaw()                                                             // Read raw code, () → int
                                                                                            // single-shot conversion, signed 16-bit
        def v = adc.readVoltage()                                                           // Read voltage, () → double V
                                                                                            // raw × lsb()
        printf("raw %d, %.5f V%n", raw, v)
        def fs = adc.fullScale()                                                            // Full scale, () → double V
                                                                                            // fixed ±2.048 V
        def lsb = adc.lsb()                                                                 // LSB, () → double V
                                                                                            // fullScale() / 32768
        printf("full scale %.3f V, lsb %.7f V%n", fs, lsb)
        adc.setDataRate(ADS1113Full.DR_250)                                                 // Set data rate, (dr 0–7) → void
                                                                                            // DR field of the Config word
        def sps = adc.dataRateSps()                                                         // Data rate, () → int SPS
                                                                                            // samples per second of the current setting
        printf("rate %d SPS%n", sps)
        adc.startContinuous()                                                               // Start continuous, () → void
                                                                                            // MODE ← 0
        Thread.sleep(50)
        def craw = adc.readContinuousRaw()                                                  // Read continuous raw, () → int
                                                                                            // CONVERSION without touching Config
        def cv = adc.readContinuous()                                                       // Read continuous, () → double V
                                                                                            // readContinuousRaw() × lsb()
        printf("continuous %d, %.5f V%n", craw, cv)
        adc.stopContinuous()                                                                // Stop continuous, () → void
                                                                                            // MODE ← 1
        def busy = adc.isConverting()                                                       // Check conversion, () → boolean
                                                                                            // CONFIG.OS == 0
        def cfg = adc.readConfig()                                                          // Read CONFIG, () → int
                                                                                            // raw 16-bit register
        printf("converting %b, config 0x%04X%n", busy, cfg)
        adc.reset()                                                                         // Reset, () → void
                                                                                            // writes the reset values back
} finally {
    connection.close()
}
