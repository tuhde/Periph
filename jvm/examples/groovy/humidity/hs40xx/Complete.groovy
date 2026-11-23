///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-groovy:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.HS40xxFull

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x54").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr)
try {
    def sensor = new HS40xxFull(conn)                                                               // Create HS40xx driver, (connection) → void

    def v = sensor.read()                                                                           // Read temperature and humidity, () → double[] {°C, %RH}
                                                                                                    // no-hold 0xF5, 5 bytes, CRC verified; one retry on mismatch
    printf("%.2f C, %.1f %%RH%n", v[0], v[1])
    def t = sensor.readTemperature()                                                                // Read temperature only, () → double °C
                                                                                                    // no-hold 0xF3, 3 bytes
    def rh = sensor.readHumidity()                                                                  // Read humidity only, () → double %RH
    def hold = sensor.readHold()                                                                    // Read with clock stretching, () → double[] {°C, %RH}
                                                                                                    // 0xE5; needs a stretching controller and SCL >= 200 kHz
    def raw = sensor.readRaw()                                                                      // Read raw values, () → int[] {humidity, temperature}
                                                                                                    // 14-bit values, CRC verified
    printf("raw RH %d T %d%n", raw[0], raw[1])
    sensor.setResolution(12, 12)                                                                    // Set resolution, (humidityBits, temperatureBits) → void
                                                                                                    // 8, 10, 12 or 14 bit; null leaves a channel unchanged
    def res = sensor.getResolution()                                                                // Get resolution, () → int[] {humidity bits, temperature bits}
    printf("resolution %d/%d bit%n", res[0], res[1])
    def reg = sensor.readRegister(0x00)                                                             // Read a register, (reg) → int
    sensor.writeRegister(0x00, 0x0F)                                                                // Write a register, (reg, value) → void
                                                                                                    // 0x0F = 14 bit for both channels (default)
    sensor.setAlertThresholds(60.0d, 55.0d, null, null, 40.0d, 38.0d, null, null)                   // Set alert thresholds, (rhHigh, rhHighReset, rhLow, rhLowReset, tempHigh, tempHighReset, tempLow, tempLowReset) → void
                                                                                                    // %RH / °C; null leaves a threshold unchanged
    sensor.setAlertPolarity(true)                                                                   // Set ALERT polarity, (activeHigh) → void
    sensor.enableInterrupt(HS40xxFull.SOURCE_RH_HIGH | HS40xxFull.SOURCE_TEMP_HIGH)                 // Enable alert sources, (source) → void
    sensor.startPeriodic(1.0d)                                                                      // Start periodic mode, (periodS) → void
                                                                                                    // 0.5, 1 or 2.5 s
    Thread.sleep(1500)
    def p = sensor.readPeriodic()                                                                   // Fetch the latest periodic result, () → double[] {°C, %RH, status}
    printf("periodic %.2f C %.1f %%RH status %d%n", p[0], p[1], (int) p[2])
    int status = sensor.pollInterrupt()                                                             // Read alert status, () → int
                                                                                                    // SOURCE_* bitmask
    sensor.onInterrupt({ int s -> println("alert " + s) } as java.util.function.IntConsumer)        // Subscribe to the ALERT pin, (callback) → void
                                                                                                    // polls at the periodic interval without a pin
    Thread.sleep(5000)
    sensor.offInterrupt()                                                                           // Unsubscribe, () → void
    sensor.disableInterrupt(HS40xxFull.SOURCE_RH_HIGH | HS40xxFull.SOURCE_TEMP_HIGH)                // Disable alert sources, (source) → void
    sensor.stopPeriodic()                                                                           // Stop periodic mode, () → void
    def id = sensor.readId()                                                                        // Read the 32-bit sensor ID, () → long
    printf("id 0x%08X%n", id)
    def dew = sensor.dewPoint()                                                                     // Compute dew point, () → double °C
                                                                                                    // one measurement, Magnus formula
    printf("dew point %.2f C%n", dew)
    sensor.softReset()                                                                              // Soft reset, () → void
                                                                                                    // stops periodic mode, waits 500 ms
} finally {
    conn.close()
}
