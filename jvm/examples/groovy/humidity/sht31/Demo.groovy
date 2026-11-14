///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.SHT31Full
import java.util.function.IntConsumer

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x44").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr)
try {
    def sensor = new SHT31Full(conn)                                                        // Create SHT31 driver, (connection) → void

    sensor.softReset()                                                                      // Soft reset, () → void
    def status = sensor.readStatus()                                                        // Read status register, () → int
    printf("status 0x%04X%n", status)
    // --- Single shots at all three repeatability levels ---
    for (String level : ["low", "medium", "high"]) {
        sensor.setRepeatability(level)                                                  // Select repeatability, (level) → void
        long t0 = System.nanoTime()
        def v = sensor.read()                                                           // Read temperature and humidity, () → double[]
        printf("%-6s %.2f C %.1f %%RH (%.1f ms)%n", level, v[0], v[1], (System.nanoTime() - t0) / 1e6)
    }
    sensor.setClockStretching(false)                                                        // Use clock-stretching commands, (on) → void
    printf("raw %s T %.2f RH %.1f%n", java.util.Arrays.toString(sensor.readRaw()), sensor.readTemperature(), sensor.readHumidity())
    // --- Periodic mode at 2 mps: ten samples ---
    sensor.startPeriodic(2.0, "high", false)                                                // Start periodic mode, (rate, repeatability, art) → void
    int got = 0
    while (got < 10) {
        def res = sensor.fetch()                                                        // Fetch the latest periodic result, () → double[] or null
        if (res == null) { Thread.sleep(50); continue }
        got++
        printf("periodic %d: %.2f C %.1f %%RH%n", got, res[0], res[1])
    }
    sensor.stopPeriodic()                                                                   // Stop periodic mode, () → void
    // --- Heater test: 10 s ---
    def before = sensor.readTemperature()                                                   // Read temperature only, () → double °C
    sensor.setHeater(true)                                                                  // Switch the heater, (on) → void
    Thread.sleep(10000)
    printf("heater status bit: %s%n", (sensor.readStatus() & 0x2000) != 0)
    printf("temperature rise: %.2f K%n", sensor.readTemperature() - before)
    sensor.setHeater(false)                                                                 // Switch the heater, (on) → void
    sensor.clearStatus()                                                                    // Clear status flags, () → void
    printf("status 0x%04X, dew point %.2f C%n", sensor.readStatus(), sensor.dewPoint())
} finally {
    conn.close()
}
