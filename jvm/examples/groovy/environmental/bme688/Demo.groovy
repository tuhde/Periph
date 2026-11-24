///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-groovy:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.environmental.Bme688Full

/**
 * Room air probe with parallel mode.
 *
 * Part 1: ten forced-mode readings (T, RH, hPa, Ω, dew point), one per 5 s.
 * Part 2: parallel mode with a 3-step heater profile (320, 250 and 180 °C;
 * repeats 1, 2, 1; shared wait 100 ms) for one minute, printing the gas
 * resistance of every heater step, then back to sleep.
 */
def bus  = (System.getenv("I2C_BUS")  ?: "1").toInteger()
def addr = Integer.decode(System.getenv("I2C_ADDR") ?: "0x76")
def connection = new I2CConnection(bus, addr)          // open I²C bus, (bus, address=0x76) → I2CConnection
try {
    def sensor = new Bme688Full(connection)            // construct driver, verifies chip ID, reads calibration, (connection) → Bme688Full
    sensor.configure(Bme688Full.OSRS_X2, Bme688Full.OSRS_X16, Bme688Full.OSRS_X1,
            Bme688Full.MODE_SLEEP, Bme688Full.FILTER_15)   // configure oversampling, mode, filter, (osrsT, osrsP, osrsH, mode, filter) → void
    sensor.setHeater(320, 150)                         // configure heater profile 0, (tempC=320 °C, durationMs=150 ms) → void

    println "--- forced mode ---"
    10.times { i ->
        def all = sensor.readAll()                     // read all four values from one TPHG cycle, () → double[4]
        double gm = Math.log(Math.max(all[2], 0.01d) / 100.0d) + 17.62d * all[0] / (243.12d + all[0])
        double dew = 243.12d * gm / (17.62d - gm)      // Magnus dew point, °C
        printf("%d: %.1f C, %.1f %%RH, %.1f hPa, %.0f Ohm, dew %.1f C%n", i, all[0], all[2], all[1], all[3], dew)
        Thread.sleep(5000)
    }

    println "--- parallel mode ---"
    sensor.startParallel([320, 250, 180] as int[], [1, 2, 1] as int[], 100d)   // start parallel mode, (tempsC, repeats, sharedWaitMs) → void
    60.times {
        sensor.readParallel().each { f ->              // read the new data fields, () → List<Field>
            printf("step %d #%d: %.1f C, %.1f %%RH, %.0f Ohm (valid=%b, stable=%b)%n",
                    f.step, f.subIndex, f.temperature, f.humidity, f.gasResistance, f.gasValid, f.heaterStable)
        }
        Thread.sleep(1000)
    }
    sensor.stopParallel()                              // back to sleep, () → void
} finally {
    connection.close()
}
