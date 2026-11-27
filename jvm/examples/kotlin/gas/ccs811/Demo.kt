///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gas.Ccs811Full
import java.util.function.IntConsumer

fun classify(eco2: Int): String = when {
    eco2 < 1000 -> "good"
    eco2 <= 1500 -> "fair"
    else -> "poor"
}

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x5A").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Ccs811Full(connection)                                                         // Create CCS811 driver, (connection) → Ccs811Full
        sensor.init()                                                                               // Check HW_ID, start application, mode 1, () → Unit

        // --- Identity ---
        // HW_ID 0x81 identifies the CCS81x family; the firmware versions tell which
        // algorithm revision computes eCO2 and TVOC.
        val hw = sensor.hardwareVersion()                                                           // Hardware ID and version, () → Pair<Int, Int>
        System.out.printf("hw 0x%02X/0x%02X %s%n", hw.first, hw.second, sensor.firmwareVersion())   // Firmware versions, () → FirmwareVersion
        System.out.printf("status 0x%02X, error 0x%02X%n", sensor.status(), sensor.error())         // STATUS and ERROR_ID, () → Int

        // --- Air-quality monitor: 30 s at 1 Hz ---
        // Mode 1 delivers a sample every second. Values settle after about 20 min of
        // warm-up; a brand-new sensor needs a 48 h burn-in.
        for (val i = 0; i < 30; i++) {
            val m = sensor.read()                                                                   // Next sample, () → Measurement
            System.out.printf("%2d s  eCO2 %4d ppm  TVOC %4d ppb  %s%n", i, m.eco2Ppm, m.tvocPpb, classify(m.eco2Ppm))
        }
        System.out.printf("eCO2 %d ppm%n", sensor.readEco2())                                       // eCO2 only, () → Int ppm
        System.out.printf("TVOC %d ppb%n", sensor.readTvoc())                                       // TVOC only, () → Int ppb

        // --- Environment compensation ---
        // Feeding the real temperature and humidity (e.g. from a BME280) corrects the
        // algorithm; without it the chip assumes 25 °C and 50 %RH.
        sensor.setEnvironment(40.0, 23.0)                                                           // Compensation data, (humidityRh %, temperatureC °C) → Unit
        if (sensor.readNtc().first != 0) {                                                          // NTC voltages, () → Pair<Int, Int> {vRefMv, vNtcMv}
            System.out.printf("NTC %.0f ohm%n", sensor.ntcResistance(10000.0))                      // NTC resistance, (rRefOhm Ω) → Double Ω
        } else {
            System.out.println("no NTC circuit fitted")
        }

        // --- Threshold interrupt: only report zone changes ---
        // With INT_THRESH the chip raises nINT only when eCO2 crosses 1000 or
        // 2000 ppm by more than the hysteresis. Polling STATUS here shows each sample.
        sensor.setThresholds(1000, 2000, 50)                                                        // Zone boundaries, (lowMediumPpm, mediumHighPpm, hysteresisPpm) → Unit
        sensor.enableInterrupt(Ccs811Full.SOURCE_THRESHOLD)                                         // Threshold interrupt, (source) → Unit
        for (val i = 0; i < 5; i++) {
            while ((sensor.pollInterrupt() and Ccs811Full.STATUS_DATA_READY) == 0) {                // STATUS, () → Int
                Thread.sleep(100)
            }
            val m = sensor.read()                                                                   // Reading releases nINT, () → Measurement
            System.out.printf("event eCO2 %d ppm %s%n", m.eco2Ppm, classify(m.eco2Ppm))
        }
        sensor.disableInterrupt(Ccs811Full.SOURCE_THRESHOLD)                                        // Threshold interrupt off, (source) → Unit

        // --- Data-ready callback ---
        // The callback gets the STATUS byte; without an INT pin the driver polls.
        sensor.enableInterrupt(Ccs811Full.SOURCE_DATA_READY)                                        // Data-ready interrupt, (source) → Unit
        sensor.onInterrupt(IntConsumer { st -> System.out.printf("callback status 0x%02X%n", st) }) // Subscribe, (callback) → Unit
        Thread.sleep(3000)
        sensor.offInterrupt()                                                                       // Unsubscribe, () → Unit
        sensor.disableInterrupt(Ccs811Full.SOURCE_DATA_READY)                                       // Data-ready interrupt off, (source) → Unit

        // --- Raw sensor data ---
        // The MOx sensor's current and voltage give its resistance; the algorithm
        // derives eCO2 and TVOC from it.
        val raw = sensor.readRaw()                                                                  // Raw data, () → Pair<Int, Double> {current µA, voltage V}
        val r = if (raw.first > 0) raw.second / (raw.first * 1e-6) else 0.0
        System.out.printf("raw %d uA, %.3f V, %.0f ohm%n", raw.first, raw.second, r)

        // --- Slower sampling: mode 2 (every 10 s) ---
        // Pulse heating saves power; pass through idle when changing modes.
        sensor.setMode(0)                                                                           // Idle, (mode) → Unit
        sensor.setMode(2)                                                                           // Every 10 s, (mode) → Unit
        for (val i = 0; i < 2; i++) {
            val m = sensor.read()                                                                   // Next sample, () → Measurement
            System.out.printf("mode 2 %s ready %b%n", m, sensor.dataReady())                        // Ready flag, () → Boolean
        }
        sensor.setMode(0)                                                                           // Idle, (mode) → Unit
        sensor.setMode(1)                                                                           // Back to every second, (mode) → Unit

        // --- Baseline ---
        // Save the baseline daily and restore it after a power cycle, but only after
        // about 20 min of warm-up; otherwise the chip relearns it from scratch.
        val baseline = sensor.getBaseline()                                                         // Baseline, () → Int
        System.out.printf("baseline 0x%04X%n", baseline)
        sensor.setBaseline(baseline)                                                                // Restore baseline, (baseline) → Unit

        // --- Wrap up ---
        // A soft reset puts the chip into boot mode; the driver starts the
        // application again in mode 1.
        sensor.softReset()                                                                          // Software reset, () → Unit
        System.out.printf("status after reset 0x%02X%n", sensor.status())                           // STATUS, () → Int
    }
}
