///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-groovy:1.5.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.HDC1080Full

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr)
try {
    def sensor = new HDC1080Full(conn)                                                               // Create HDC1080 driver, (connection) → void

    sensor.init()                                                                               // Write default configuration, () → void
    printf("serial id 0x%010X%n", sensor.serialId())                                            // Read the 40-bit serial ID, () → long
    printf("config 0x%04X%n", sensor.readConfig())                                              // Read the configuration register, () → int
    def v = sensor.read()                                                                       // Read temperature and humidity, () → double[] {°C, %RH}
    printf("%.2f C, %.1f %%RH%n", v[0], v[1])
    printf("temperature %.2f C%n", sensor.readTemperature())
    // sensor.readTemperature() → double °C, single mode
    printf("humidity %.1f %%RH%n", sensor.readHumidity())
    // sensor.readHumidity() → double %RH, single mode
    def raw = sensor.readRaw()                                                                  // Read raw words, () → int[] {temperature, humidity}
    printf("raw T %d raw RH %d%n", raw[0], raw[1])

    sensor.setResolution(11, 8)                                                                 // Set resolution, (temperatureBits, humidityBits) → void
    printf("conversion time %.2f ms%n", sensor.conversionTime() * 1000.0)
    // sensor.conversionTime() → double s, typical T + RH time
    sensor.setResolution(14, 14)

    sensor.setHeater(true)                                                                      // Switch the heater, (on) → void
    printf("heater on %s%n", sensor.isHeaterOn())
    // sensor.isHeaterOn() → boolean
    sensor.setHeater(false)
    printf("battery low %s%n", sensor.batteryLow())
    // sensor.batteryLow() → boolean, true when VDD is below 2.8 V
    sensor.softwareReset()                                                                      // Software reset, () → void

    printf("manufacturer 0x%04X%n", sensor.manufacturerId())                                    // Manufacturer ID (0x5449), () → int
    printf("device 0x%04X%n", sensor.deviceId())                                                // Device ID (0x1050), () → int
} finally {
    conn.close()
}
