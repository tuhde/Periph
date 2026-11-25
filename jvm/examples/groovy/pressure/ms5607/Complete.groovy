///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Ms5607Full

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x77").replaceFirst("^0[xX]", ""), 16)

def connection = new I2CConnection(bus, addr)
try {
    def sensor = new Ms5607Full(connection)                                                                         // Create MS5607 driver, (connection) → Ms5607Full
    sensor.init()                                                                                                   // Reset, read PROM, verify CRC-4, () → void
    def m = sensor.read()                                                                                           // Read temperature and pressure, () → double[] {°C, Pa}
                                                                                                                    // OSR 4096, second-order compensation applied
    System.out.printf("%.2f C, %.1f Pa%n", m[0], m[1])
    System.out.printf("pressure %.1f Pa%n", sensor.readPressure())                                                  // Read pressure only, () → double Pa
    System.out.printf("temperature %.2f C%n", sensor.readTemperature())                                             // Read temperature only, () → double °C
                                                                                                                    // converts D2 only
    def raw = sensor.readRaw()                                                                                      // Read raw ADC codes, () → int[] {d1, d2}
                                                                                                                    // 24-bit pressure and temperature codes
    System.out.printf("raw D1 %d D2 %d%n", raw[0], raw[1])
    def c = sensor.compensate(raw[0], raw[1])                                                                       // Compensate raw codes, (d1, d2, secondOrder = true) → double[] {°C, Pa}
                                                                                                                    // pure computation, no bus access
    def c1 = sensor.compensate(raw[0], raw[1], false)
    System.out.printf("second order %.1f Pa, first order %.1f Pa%n", c[1], c1[1])
    sensor.setOversampling(1024)                                                                                    // Select oversampling ratio, (osr) → void
                                                                                                                    // 256, 512, 1024, 2048 or 4096
    println("osr " + sensor.getOversampling())                                                                      // Current oversampling ratio, () → int
    println("conversion time " + sensor.conversionTime() + " s")                                                    // Max conversion time, (osr = null) → double s
    sensor.startConversion(Ms5607Full.TEMPERATURE)                                                                  // Start a conversion, (kind, osr = null) → void
                                                                                                                    // PRESSURE = D1, TEMPERATURE = D2; non-blocking
    Thread.sleep(12)
    println("adc " + sensor.readAdc())                                                                              // Read the ADC result, () → int
                                                                                                                    // only after the conversion time, 24-bit
    sensor.setOversampling(4096)
    println("prom " + sensor.readProm().toList().toList())                                                          // Read the PROM words, () → int[8]
    println("coefficients " + sensor.coefficients().toList())                                                       // Calibration coefficients, () → int[] {C1 .. C6}
    println("crc ok " + sensor.promCrcOk())                                                                         // Verify the PROM CRC-4, () → boolean
    println("serial " + sensor.serialCode())                                                                        // Serial code, () → int
                                                                                                                    // PROM word 7 bits 15:4
    System.out.printf("altitude %.2f m%n", sensor.altitude())                                                       // Altitude from pressure, (seaLevelPa = 101325.0) → double m
                                                                                                                    // international barometric formula
    sensor.reset()                                                                                                  // Reset and reload PROM, () → void
                                                                                                                    // 0x1E, 3 ms wait
} finally {
    connection.close()
}
