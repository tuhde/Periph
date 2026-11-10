///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.ADS1115Full

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, ADS1115Full.DEFAULT_ADDRESS)                    // open I²C bus, device address, (bus, address=0x48) → I2CConnection
try {
        def adc = new ADS1115Full(connection)                                               // construct driver, (connection) → ADS1115Full
                                                                                            // no bus traffic; Config word is rebuilt per conversion
        def raw = adc.readRaw(2)                                                            // Read raw code, (channel=0) → int
                                                                                            // single-shot conversion, signed 16-bit
        def v = adc.readVoltage(2)                                                          // Read voltage, (channel=0) → double V
                                                                                            // raw × lsb()
        printf("raw %d, %.5f V%n", raw, v)
        adc.setGain(ADS1115Full.PGA_4_096)                                                  // Set gain, (pga 0–5) → void
                                                                                            // ±4.096 V full scale
        printf("gain %d%n", adc.getGain())
        def fs = adc.fullScale()                                                            // Full scale, () → double V
                                                                                            // ±FSR of the current gain
        def lsb = adc.lsb()                                                                 // LSB, () → double V
                                                                                            // fullScale() / 32768
        printf("full scale %.3f V, lsb %.7f V%n", fs, lsb)
        adc.setDataRate(ADS1115Full.DR_250)                                                 // Set data rate, (dr 0–7) → void
                                                                                            // DR field of the Config word
        def sps = adc.dataRateSps()                                                         // Data rate, () → int SPS
                                                                                            // samples per second of the current setting
        printf("rate %d SPS%n", sps)
        def d = adc.readDifferential(ADS1115Full.DIFF_2_3)                                  // Read differential, (pair 0–3) → double V
                                                                                            // single-shot MUX = pair
        def draw = adc.readDifferentialRaw(ADS1115Full.DIFF_0_1)                            // Read differential raw, (pair 0–3) → int
                                                                                            // signed 16-bit code
        printf("differential %.6f V, raw %d%n", d, draw)
        adc.startContinuous(ADS1115Full.MUX_AIN0_GND)                                       // Start continuous, (mux=MUX_AIN0_GND 0–7) → void
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
        adc.setThresholds(0.5, 3.0)                                                         // Set thresholds, (low V, high V) → void
                                                                                            // converted with the current lsb(), clamped
        adc.setLowThresholdRaw(4000)                                                        // Set low threshold, (raw) → void
                                                                                            // LO_THRESH ← raw & 0xFFFF
        adc.setHighThresholdRaw(20000)                                                      // Set high threshold, (raw) → void
                                                                                            // HI_THRESH ← raw & 0xFFFF
        def th = adc.getThresholdsRaw()                                                     // Get thresholds, () → int[] {low, high}
                                                                                            // read back, sign-extended
        printf("thresholds %d %d%n", th[0], th[1])
        adc.configureComparator(ADS1115Full.COMP_WINDOW, ADS1115Full.ACTIVE_LOW, true, ADS1115Full.QUEUE_2)// Configure comparator, (mode, polarity, latch=false, queue=QUEUE_1) → void
                                                                                            // window mode, latching, 2-sample queue
        adc.clearAlert()                                                                    // Clear alert, () → void
                                                                                            // reads CONVERSION to release the latch
        adc.disableComparator()                                                             // Disable comparator, () → void
                                                                                            // COMP_QUE ← 11
        adc.enableInterrupt(ADS1115Full.SOURCE_CONVERSION_READY)                            // Enable interrupt, (source) → void
                                                                                            // ALERT/RDY signals conversion-ready
        def status = adc.pollInterrupt()                                                    // Poll interrupt, () → int source
                                                                                            // enabled source if ALERT/RDY is asserted
        printf("poll %d%n", status)
        adc.onInterrupt({ int s -> println("alert " + s) } as java.util.function.IntConsumer)// Subscribe, (callback) → void
                                                                                            // pin edge, or a polling thread without an InputPin
        Thread.sleep(1000)
        adc.offInterrupt()                                                                  // Unsubscribe, () → void
                                                                                            // stops delivery
        adc.disableInterrupt(ADS1115Full.SOURCE_CONVERSION_READY)                           // Disable interrupt, (source) → void
                                                                                            // releases ALERT/RDY
        adc.reset()                                                                         // Reset, () → void
                                                                                            // writes the reset values back
} finally {
    connection.close()
}
