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
                                                                                                    // 15 ms power-up wait, soft reset, 15 ms
    def v = sensor.read()                                                                           // Read temperature and humidity, () → double[] {°C, %RH}
                                                                                                    // no hold master, CRC checked, one retry on a mismatch
    printf("%.2f C, %.1f %%RH%n", v[0], v[1])
    printf("humidity %.1f %%RH%n", sensor.readHumidity())
    // sensor.readHumidity() → double %RH, not clamped
    printf("temperature %.2f C%n", sensor.readTemperature())
    // sensor.readTemperature() → double °C
    printf("compensated humidity %.1f %%RH%n", sensor.readHumidityCompensated())
    // sensor.readHumidityCompensated() → double %RH, -0.15 %RH/°C relative to 25 °C
    def raw = sensor.readRaw()                                                                      // Read raw codes, () → int[] {temperature, humidity}
                                                                                                    // status bits cleared
    printf("raw T %d raw RH %d%n", raw[0], raw[1])

    sensor.setResolution(3)                                                                         // Set resolution, (res) → void
                                                                                                    // 0 = 12/14 bit, 1 = 8/12, 2 = 10/13, 3 = 11/11
    printf("resolution setting %d%n", sensor.getResolution())
    // sensor.getResolution() → int
    sensor.setResolution(0)

    sensor.setHeater(true)                                                                          // Switch the heater, (on) → void
                                                                                                    // about 5.5 mW; readings drift while on
    printf("heater on %s%n", sensor.heater())
    // sensor.heater() → boolean
    sensor.setHeater(false)
    printf("battery low %s%n", sensor.batteryLow())
    // sensor.batteryLow() → boolean, true when VDD is below about 2.25 V
    def reg = sensor.readUserRegister()                                                             // Read the user register, () → int
                                                                                                    // 0x02 after reset
    printf("user register 0x%02X%n", reg)
    sensor.setHoldMaster(false)                                                                     // Use hold-master commands, (on) → void
                                                                                                    // needs bus-layer clock stretching; off polls the NACKed read header
    printf("dew point %.2f C%n", sensor.dewPoint())
    // sensor.dewPoint() → double °C, one measurement
    sensor.softReset()                                                                              // Soft reset, () → void
                                                                                                    // 0xFE, waits 15 ms; the heater bit survives
} finally {
    conn.close()
}
