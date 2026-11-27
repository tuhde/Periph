///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Spl06Full
import java.util.function.IntConsumer

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x77").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Spl06Full(connection)                                                          // Create SPL06 driver, (connection) → Spl06Full

        // --- Identity and calibration ---
        // init() checks ID = 0x10; the nine coefficients are factory-trimmed per
        // part and drive every compensated value below.
        sensor.init()                                                                               // Check ID, read coefficients, apply defaults, () → void
        System.out.println("ID 0x10 verified " + sensor.coefficients())                             // Decoded calibration, () → Coefficients

        // --- Altimeter: 20 s at 1 Hz, altitude relative to the first reading ---
        // The first pressure becomes the reference level, so the altitude column
        // shows how far the sensor moved since start (about 12 Pa per metre).
        val p0 = sensor.readPressure()                                                              // Reference pressure, () → double Pa
        for (i in 0 until 20) {
            val m = sensor.read()                                                                   // Temperature and pressure, () → Measurement
            val t = sensor.readTemperature()                                                        // Temperature only, () → double °C
            val h = sensor.altitude(p0)                                                             // Altitude vs. reference, (seaLevelPa) → double m
            System.out.printf("%d s  %.2f C (%.2f C)  %.2f Pa  %.2f m%n", i, m.temperatureC, t, m.pressurePa, h)
            Thread.sleep(1000)
        }

        // --- Oversampling: noise and measurement time at 1x, 16x and 128x ---
        // More oversampling means less noise but longer measurements; above 8x
        // the driver sets P_SHIFT automatically.
        for (osr in intArrayOf(1, 16, 128)) {
            sensor.setPressureConfig(osr, 1)                                                        // Pressure oversampling, (oversampling, rate) → void
            val v = DoubleArray(10)
            val start = System.currentTimeMillis()
            for (j in 0 until 10) v[j] = sensor.readPressure()                                      // Pressure, () → double Pa
            val per = (System.currentTimeMillis() - start) / 10.0
            val mean = v.average()
            val sd = Math.sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
            System.out.printf("%dx: mean %.2f Pa  sd %.2f Pa  %.1f ms per reading%n", osr, mean, sd, per)
        }

        // --- Variometer: background mode at 8 Hz ---
        // The chip measures on its own; readContinuous only fetches the latest
        // results, so the host never waits for a conversion it started itself.
        sensor.setPressureConfig(16, 8)                                                             // 16x, 8 Hz, (oversampling, rate) → void
        sensor.setTemperatureConfig(1, 8)                                                           // 1x, 8 Hz, (oversampling, rate) → void
        sensor.startContinuous(true, true)                                                          // Background mode, (pressure, temperature) → void
        var last = Double.NaN
        repeat(16) {
            val c = sensor.readContinuous()                                                         // Latest results, () → Measurement
            if (!last.isNaN()) System.out.printf("%.2f Pa  climb %.2f m/s%n", c.pressurePa, -(c.pressurePa - last) / 12 * 8)
            last = c.pressurePa
        }
        println("ready flags " + sensor.dataReady())                                                // Ready flags, () → boolean[] {pressureReady, temperatureReady}
        sensor.stop()                                                                               // Standby, () → void

        // --- FIFO: let the chip collect 32 results, then read them in one go ---
        // Each entry's LSB marks it as pressure or temperature; the host can sleep
        // while the chip fills the FIFO (32 entries = 2 s at 8 Hz for both).
        sensor.flushFifo()                                                                          // Empty the FIFO, () → void
        sensor.enableFifo()                                                                         // FIFO on, () → void
        sensor.startContinuous()                                                                    // Background mode, () → void
        for (i in 0 until 50) { if (sensor.fifoStatus().second) break; Thread.sleep(100) }          // Wait for full, () → boolean[] {empty, full}
        sensor.stop()                                                                               // Standby, () → void
        val entries = sensor.readFifo()                                                             // Drain FIFO, () → List<FifoEntry>
        val pressures = entries.filter { it.kind == Spl06Full.KIND_PRESSURE }.map { it.value }
        System.out.printf("%d FIFO entries, %d pressure, mean %.2f Pa%n", entries.size, pressures.size, if (pressures.isEmpty()) 0.0 else pressures.average())
        sensor.disableFifo()                                                                        // FIFO off, () → void

        // --- Data-ready interrupt ---
        // SDO doubles as the interrupt line; the callback receives the INT_STS bits.
        // Without an INT pin on the connection the driver polls INT_STS instead.
        sensor.enableInterrupt(Spl06Full.SOURCE_PRESSURE)                                           // Pressure-ready interrupt, (source) → void
        sensor.pollInterrupt()                                                                      // Clear stale status, () → int
        sensor.onInterrupt(IntConsumer { status -> System.out.printf("interrupt 0x%02X%n", status) }) // Subscribe, (callback) → void
        sensor.startContinuous(true, true)                                                          // Background mode, (pressure, temperature) → void
        Thread.sleep(2000)
        sensor.offInterrupt()                                                                       // Unsubscribe, () → void
        sensor.disableInterrupt(Spl06Full.SOURCE_PRESSURE)                                          // Disable source, (source) → void
        sensor.stop()                                                                               // Standby, () → void

        // --- Compensation by hand ---
        // compensate() turns raw register values into SI units with the stored
        // coefficients; useful for logging raw data and converting it later.
        sensor.setPressureConfig(16, 1)                                                             // Back to 16x, (oversampling, rate) → void
        sensor.setTemperatureConfig(1, 1)                                                           // Back to 1x, (oversampling, rate) → void
        val raw = sensor.readRaw()                                                                  // Raw results, () → int[] {praw, traw}
        System.out.println("raw " + raw[0] + " " + raw[1] + " -> " + sensor.compensate(raw[0], raw[1], 16, 1)) // Compensate, (praw, traw, pOversampling, tOversampling) → Measurement

        // --- Wrap up ---
        // Standby, empty FIFO and a soft reset leave the chip as after power-up
        // (plus the driver defaults).
        sensor.stop()                                                                               // Standby, () → void
        sensor.flushFifo()                                                                          // Empty the FIFO, () → void
        sensor.reset()                                                                              // Soft reset and defaults, () → void
    }
}
