///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-groovy:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Ms5607Full

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x77").replaceFirst("^0[xX]", ""), 16)

def connection = new I2CConnection(bus, addr)
try {
    def sensor = new Ms5607Full(connection)                                                                         // Create MS5607 driver, (connection) → Ms5607Full
    sensor.init()                                                                                                   // Reset, read PROM, verify CRC-4, () → void

    // --- Calibration data ---
    // Six coefficients and a CRC-4 are factory-programmed into the PROM.
    println("PROM " + sensor.readProm().toList())                                                                   // Read the PROM words, () → int[8]
    println("CRC ok " + sensor.promCrcOk())                                                                         // Verify the PROM CRC-4, () → boolean
    println("coefficients " + sensor.coefficients().toList() + " serial " + sensor.serialCode())

    // --- Barometric altimeter / variometer: 20 s, one reading per second ---
    def reference = sensor.readPressure()                                                                           // Read pressure only, () → double Pa
    def previous = 0.0
    def lowest = 100.0
    for (int i = 0; i < 20; i++) {
        def m = sensor.read()                                                                                       // Read temperature and pressure, () → double[] {°C, Pa}
        lowest = Math.min(lowest, m[0])
        def height = sensor.altitude(reference)                                                                     // Altitude from pressure, (seaLevelPa) → double m
        def climb = i == 0 ? 0.0d : height - previous
        previous = height
        System.out.printf("%.2f C  %.1f Pa  %.2f m  %+.2f m/s%n", m[0], m[1], height, climb)
        Thread.sleep(1000)
    }

    // --- Noise and time versus oversampling: ten readings each ---
    for (int osr in [256, 512, 1024, 2048, 4096]) {
        sensor.setOversampling(osr)                                                                                 // Select oversampling ratio, (osr) → void
        def start = System.nanoTime()
        def lo = Double.MAX_VALUE
        def hi = -Double.MAX_VALUE
        10.times {
            def v = sensor.readPressure()
            lo = Math.min(lo, v)
            hi = Math.max(hi, v)
        }
        System.out.printf("OSR %4d  %.1f ms/read  spread %.1f Pa%n", osr, (System.nanoTime() - start) / 1.0e7, hi - lo)
    }
    sensor.setOversampling(4096)

    // --- Raw codes with and without the second-order correction ---
    def raw = sensor.readRaw()                                                                                      // Read raw ADC codes, () → int[] {d1, d2}
    println("raw " + raw[0] + " " + raw[1])
    def first = sensor.compensate(raw[0], raw[1], false)                                                            // Compensate raw codes, (d1, d2, secondOrder) → double[] {°C, Pa}
    def second = sensor.compensate(raw[0], raw[1])
    System.out.printf("first order  %.2f C %.1f Pa%n", first[0], first[1])
    System.out.printf("second order %.2f C %.1f Pa%n", second[0], second[1])
    System.out.printf("lowest temperature seen %.2f C%n", lowest)

    // --- Non-blocking conversion, then reset ---
    sensor.startConversion(Ms5607Full.PRESSURE)                                                                     // Start a conversion, (kind, osr = null) → void
    Thread.sleep(12)
    println("D1 " + sensor.readAdc())                                                                               // Read the ADC result, () → int
    sensor.reset()                                                                                                  // Reset and reload PROM, () → void
} finally {
    connection.close()
}
