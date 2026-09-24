///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-groovy:1.2.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.tof.VL53L1XMinimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, VL53L1XMinimal.DEFAULT_ADDRESS)                   // open I²C bus, device address, (bus, address=0x29) → I2CConnection
try {
    def sensor = new VL53L1XMinimal(connection)                                  // Create VL53L1X driver, (connection) → VL53L1XMinimal

    50.times {
        def d = sensor.distance()                                                // Measure distance, () → int mm
        if (sensor.rangeValid()) {                                               // Check last measurement, () → boolean
            println "$d mm"
        } else {
            println "out of range"
        }
        Thread.sleep(200)
    }
} finally {
    connection.close()
}
