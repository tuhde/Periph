///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-groovy:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.TSL2561Full
import it.uhde.periph.chips.light.TSL2561Minimal

import java.util.function.IntConsumer

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, TSL2561Minimal.DEFAULT_ADDRESS)                            // open I²C bus, device address, (bus, address=0x39) → I2CConnection
try {
    def sensor = new TSL2561Full(connection)                                                       // construct driver, (connection) → TSL2561Full
    // powers up, CONTROL read back, TIMING ← 0x02 (1×, 402 ms), waits 450 ms

    println(String.format("id 0x%02X", sensor.readId()))                                           // Read the ID register, () → int
    // bits 7:4 = 1 for the TSL2561, bits 3:0 revision
    println(sensor.readLux())                                                                      // Read illuminance, () → double lux
    // throws SaturatedException on a saturated channel
    int[] raw = sensor.readRaw()                                                                   // Read both channels, () → int[] {ch0, ch1}
    // one block read: ch0 visible+IR, ch1 IR
    def r = sensor.readLuxRaw()                                                                    // Lux plus counts, () → Reading {lux, ch0, ch1}
    println("${r.lux} lx, ch0 ${r.ch0}, ch1 ${r.ch1}")
    println(TSL2561Minimal.calculateLux(raw[0], raw[1], 1, 402, "T"))                              // Convert counts, (ch0, ch1, gain, integrationMs, pkg) → double lux

    sensor.setGain(16)                                                                             // Set ADC gain, (gain 1|16) → void
    // 16× for dim light; saturates indoors at ~1–2 klx
    sensor.setIntegrationTime(101)                                                                 // Set integration time, (ms 14|101|402) → void
    // the conversion in progress keeps the old setting
    println("${sensor.getGain()}x ${sensor.getIntegrationTime()} ms")                              // Current settings, () → int, () → int ms
    Thread.sleep(250)
    sensor.setPackage("CS")                                                                        // Select lux coefficients, (pkg "T"|"FN"|"CL"|"CS") → void
    // T, FN and CL share one set; CS has its own
    println(sensor.readLux())
    sensor.setPackage("T")
    println(sensor.autoRange())                                                                    // Auto-range and read lux, () → double lux
    // 16×/402 ms … 1×/13.7 ms until no saturation and ch0 > 100

    sensor.startManual()                                                                           // Start a host-timed integration, () → void
    // INTEG = 11, MANUAL = 1
    Thread.sleep(200)
    sensor.stopManual()                                                                            // Stop the integration, () → void
    // INTEG = 11, MANUAL = 0; counts are ready
    println(sensor.readRaw())
    sensor.setIntegrationTime(402)
    sensor.setGain(1)

    sensor.setThresholds(100, 20000)                                                               // Set channel-0 window, (low, high counts 0–65535) → void
    // word writes of 0x2–0x3 and 0x4–0x5
    println(sensor.getThresholds())                                                                // Read the window, () → int[] {low, high}
    sensor.configureInterrupt(1, 2)                                                                // Interrupt mode, (mode 0|1|2, persist 0–15) → void
    // level interrupt after 2 cycles outside the window
    println(sensor.pollInterrupt())                                                                // Check ch0 against the window, () → boolean
    // does not clear the chip's interrupt
    sensor.clearInterrupt()                                                                        // Release a level interrupt, () → void
    // command byte 0xC0
    sensor.onInterrupt({ int ch0 -> println("interrupt ${ch0}") } as IntConsumer)                  // Subscribe, (callback(ch0), intPin=null) → void
    // falling edge on INT, or poll once per integration
    Thread.sleep(10000)
    sensor.offInterrupt()                                                                          // Unsubscribe, () → void
    sensor.configureInterrupt(0, 0)

    println(sensor.isPowered())                                                                    // Power state, () → boolean
    // CONTROL reads 0x03 when powered
    sensor.powerDown()                                                                             // Power down, () → void
    // CONTROL = 0x00; registers keep their values
    sensor.powerUp()                                                                               // Power up, () → void
    // CONTROL = 0x03; integration restarts
} finally {
    connection.close()
}
