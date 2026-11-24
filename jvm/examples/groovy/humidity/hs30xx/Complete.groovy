///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.HS30xxFull

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x44").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr)
try {
    def sensor = new HS30xxFull(conn)                                                               // Create HS30xx driver, (connection) → void
    // Replace the empty body with code that cuts and restores the sensor supply (MOSFET or GPIO).
    Runnable powerCycle = { -> } as Runnable

    def v = sensor.read()                                                                           // Read temperature and humidity, () → double[] {°C, %RH}
                                                                                                    // request, 40 ms wait, 4-byte fetch; one retry on stale data
    printf("%.2f C, %.1f %%RH%n", v[0], v[1])
    def t = sensor.readTemperature()                                                                // Read temperature only, () → double °C
    def rh = sensor.readHumidity()                                                                  // Read humidity only, () → double %RH
                                                                                                    // 2-byte fetch
    def raw = sensor.readRaw()                                                                      // Read raw values, () → int[] {humidity, temperature, status}
                                                                                                    // 14-bit values; status 0 = valid, 1 = stale
    printf("raw RH %d T %d status %d%n", raw[0], raw[1], raw[2])
    sensor.setMeasurementWait(60)                                                                   // Override the post-request wait, (ms) → void
                                                                                                    // default 40 ms; lower resolutions need less
    def dew = sensor.dewPoint()                                                                     // Compute dew point, () → double °C
                                                                                                    // one measurement, Magnus formula
    printf("dew point %.2f C%n", dew)

    // NVM access only works within 10 ms after power-up, so it needs the powerCycle helper above.
    def res = sensor.getResolution(powerCycle)                                                      // Get NVM resolution, (powerCycle) → int[] {humidity bits, temperature bits}
    printf("resolution %d/%d bit%n", res[0], res[1])
    sensor.setResolution(12, 12, powerCycle)                                                        // Set NVM resolution, (humidityBits, temperatureBits, powerCycle) → void
                                                                                                    // 8, 10, 12 or 14 bit; null leaves a channel unchanged
    def id = sensor.readId(powerCycle)                                                              // Read the 32-bit sensor ID, (powerCycle) → long
    printf("id 0x%08X%n", id)
    sensor.enterProgrammingMode()                                                                   // Enter programming mode, () → void
                                                                                                    // only within 10 ms after power-up
    def reg = sensor.readNvm(0x06)                                                                  // Read an NVM register, (reg) → int
                                                                                                    // 0x06 humidity / 0x11 temperature resolution, 0x1E / 0x1F ID
    printf("NVM 0x06 = 0x%04X%n", reg)
    sensor.exitProgrammingMode()                                                                    // Leave programming mode, () → void
} finally {
    conn.close()
}
