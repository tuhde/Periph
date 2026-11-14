///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.Si7021Full

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr)
try {
    def sensor = new Si7021Full(conn)                                                               // Create Si7021 driver, (connection) → void

    def v = sensor.read()                                                                           // Read temperature and humidity, () → double[] {°C, %RH}
                                                                                                    // RH without hold master, temperature of the same conversion
    printf("%.2f C, %.1f %%RH%n", v[0], v[1])
    printf("humidity %.1f %%RH%n", sensor.readHumidity())
    // sensor.readHumidity() → double %RH
    printf("temperature %.2f C%n", sensor.readTemperature())
    // sensor.readTemperature() → double °C, new conversion
    printf("previous %.2f C%n", sensor.readTemperaturePrevious())
    // sensor.readTemperaturePrevious() → double °C, command 0xE0, no new conversion
    printf("raw %d %d%n", sensor.readRawHumidity(), sensor.readRawTemperature())
    // sensor.readRawHumidity() / readRawTemperature() → int, 16-bit codes with status bits

    sensor.setResolution(11, 11)                                                                    // Set resolution, (rhBits, temperatureBits) → void
                                                                                                    // (12, 14) / (8, 12) / (10, 13) / (11, 11)
    printf("resolution %s%n", java.util.Arrays.toString(sensor.getResolution()))
    // sensor.getResolution() → int[] {rhBits, temperatureBits}
    printf("conversion time %.4f s%n", sensor.conversionTime())
    // sensor.conversionTime() → double s, typical RH + temperature time
    sensor.setResolution(12, 14)

    sensor.setHeater(true)                                                                          // Switch the heater, (on) → void
                                                                                                    // about 3.1 mA; readings drift while on
    printf("heater on %s%n", sensor.isHeaterOn())
    // sensor.isHeaterOn() → boolean
    sensor.setHeater(false)
    printf("VDD low %s%n", sensor.vddLow())
    // sensor.vddLow() → boolean, true when VDD is below about 1.9 V
    def reg = sensor.readUserRegister()                                                             // Read USER_REG_1, () → int
                                                                                                    // 0x3A after reset
    sensor.writeUserRegister(reg)                                                                   // Write USER_REG_1, (value) → void
                                                                                                    // reserved bits are preserved
    sensor.setClockStretching(false)                                                                // Use hold-master commands, (on) → void
                                                                                                    // needs bus-layer support; off polls the NACKed read header

    printf("serial number 0x%016X%n", sensor.serialNumber())
    // sensor.serialNumber() → long
    printf("device type 0x%02X%n", sensor.deviceType())
    // sensor.deviceType() → int, 0x15 = Si7021
    printf("firmware 0x%02X%n", sensor.firmwareRevision())
    // sensor.firmwareRevision() → int, 0xFF = firmware 1.0
    printf("dew point %.2f C%n", sensor.dewPoint())
    // sensor.dewPoint() → double °C, one measurement, Magnus formula
    sensor.softReset()                                                                              // Software reset, () → void
                                                                                                    // 0xFE, waits 15 ms
} finally {
    conn.close()
}
