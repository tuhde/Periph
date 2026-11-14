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

    def v = sensor.read()                                                                   // Read temperature and humidity, () → double[] {°C, %RH}
                                                                                            // single shot, high repeatability, CRC checked
    printf("%.2f C, %.1f %%RH%n", v[0], v[1])
    printf("temperature %.2f C%n", sensor.readTemperature())
    // sensor.readTemperature() → double °C
    printf("humidity %.1f %%RH%n", sensor.readHumidity())
    // sensor.readHumidity() → double %RH
    def raw = sensor.readRaw()                                                              // Read raw words, () → int[] {temperature, humidity}
                                                                                            // 16-bit ticks
    printf("raw %s%n", java.util.Arrays.toString(raw))
    sensor.setRepeatability("low")                                                          // Select repeatability, (level) → void
                                                                                            // "low" / "medium" / "high" (4 / 6 / 15 ms)
    printf("low repeatability: %s%n", java.util.Arrays.toString(sensor.read()))
    sensor.setRepeatability("high")                                                         // Select repeatability, (level) → void
    sensor.setClockStretching(false)                                                        // Use clock-stretching commands, (on) → void
                                                                                            // needs bus-layer support; off polls the NACKed read header
    sensor.startPeriodic(2.0, "high", false)                                                // Start periodic mode, (rate, repeatability, art) → void
                                                                                            // rate 0.5 / 1 / 2 / 4 / 10 mps; art = true selects the 4 mps ART mode
    Thread.sleep(1000)
    def fetched = sensor.fetch()                                                            // Fetch the latest periodic result, () → double[] or null
                                                                                            // null while the read header is NACKed
    printf("fetch %s%n", java.util.Arrays.toString(fetched))
    sensor.stopPeriodic()                                                                   // Stop periodic mode, () → void
                                                                                            // break command 0x3093, waits 1 ms
    sensor.setHeater(true)                                                                  // Switch the heater, (on) → void
                                                                                            // about 5-25 mW; readings are unusable while on
    def status = sensor.readStatus()                                                        // Read status register, () → int
                                                                                            // CRC verified; bit 13 = heater on
    printf("status 0x%04X%n", status)
    sensor.setHeater(false)                                                                 // Switch the heater, (on) → void
    sensor.clearStatus()                                                                    // Clear status flags, () → void
                                                                                            // command 0x3041
    printf("dew point %.2f C%n", sensor.dewPoint())
    // sensor.dewPoint() → double °C, one measurement, Magnus formula
    sensor.softReset()                                                                      // Soft reset, () → void
                                                                                            // 0x30A2, waits 1 ms, back to single shot
} finally {
    conn.close()
}
