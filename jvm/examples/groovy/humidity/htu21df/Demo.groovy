///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.HTU21DFFull

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr)
try {
    def sensor = new HTU21DFFull(conn)                                                               // Create HTU21D-F driver, (connection) → void

    sensor.init()                                                                                   // Soft reset, () → void
    sensor.softReset()                                                                              // Soft reset, () → void
    printf("user register 0x%02X, resolution %d%n", sensor.readUserRegister(), sensor.getResolution())
    // sensor.readUserRegister() → int ; sensor.getResolution() → int

    // --- Five readings at 12/14 bit ---
    for (int i = 0; i < 5; i++) {
        def w = sensor.read()                                                                       // Read temperature and humidity, () → double[] {°C, %RH}
        printf("%.2f C %.1f %%RH (compensated %.1f) dew point %.2f C%n", w[0], w[1], sensor.readHumidityCompensated(), sensor.dewPoint())
        // sensor.readHumidityCompensated() → double %RH ; sensor.dewPoint() → double °C
        Thread.sleep(2000)
    }
    def raw = sensor.readRaw()                                                                      // Read raw codes, () → int[] {temperature, humidity}
    printf("raw T %d raw RH %d%n", raw[0], raw[1])

    // --- 8/12 bit: shorter conversion, coarser resolution ---
    sensor.setResolution(1)                                                                         // Set resolution, (res) → void
    def t0 = System.currentTimeMillis()
    def t812 = sensor.readTemperature()
    def rh812 = sensor.readHumidity()
    printf("8/12 bit: %.2f C %.1f %%RH in %d ms%n", t812, rh812, System.currentTimeMillis() - t0)
    sensor.setResolution(0)
    sensor.setHoldMaster(false)                                                                     // Use hold-master commands, (on) → void

    // --- Heater self-check: 10 s ---
    def rhBefore = sensor.readHumidity()
    def tBefore = sensor.readTemperature()
    sensor.setHeater(true)                                                                          // Switch the heater, (on) → void
    Thread.sleep(10000)
    printf("heater on %s%n", sensor.heater())
    // sensor.heater() → boolean
    printf("RH drop %.1f %%RH, temperature rise %.2f K%n", rhBefore - sensor.readHumidity(), sensor.readTemperature() - tBefore)
    sensor.setHeater(false)
    printf("battery low %s%n", sensor.batteryLow())
    // sensor.batteryLow() → boolean
} finally {
    conn.close()
}
