///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-groovy:1.5.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.HDC1080Full

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr)
try {
    def sensor = new HDC1080Full(conn)                                                               // Create HDC1080 driver, (connection) → void

    sensor.init()                                                                               // Write default configuration, () → void
    printf("serial id 0x%010X%n", sensor.serialId())

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    15.times {
        def w = sensor.read()                                                                   // Read temperature and humidity, () → double[] {°C, %RH}
        double g = Math.log(Math.max(w[1], 0.01d) / 100.0d) + 17.62d * w[0] / (243.12d + w[0])
        double dew = 243.12d * g / (17.62d - g)                                                 // Magnus dew point, °C
        printf("%.2f C %.1f %%RH dew point %.2f C%n", w[0], w[1], dew)
        Thread.sleep(2000)
    }

    // --- Heater test: 10 s ---
    def rh0 = sensor.readHumidity()
    def t0 = sensor.readTemperature()
    sensor.setHeater(true)                                                                      // Switch the heater, (on) → void
    Thread.sleep(10000)
    def rh1 = sensor.readHumidity()
    def t1 = sensor.readTemperature()
    printf("RH drop %.1f %%RH, temperature rise %.2f K%n", rh0 - rh1, t1 - t0)
    sensor.setHeater(false)

    // --- Resolution comparison ---
    sensor.setResolution(11, 8)                                                                 // Set resolution, (temperatureBits, humidityBits) → void
    long ts = System.currentTimeMillis()
    def r = sensor.read()
    printf("11/8 bit: %.2f C %.1f %%RH in %d ms%n", r[0], r[1], System.currentTimeMillis() - ts)
    sensor.setResolution(14, 14)
    printf("battery low %s%n", sensor.batteryLow())
    // sensor.batteryLow() → boolean, true when VDD is below 2.8 V
} finally {
    conn.close()
}
