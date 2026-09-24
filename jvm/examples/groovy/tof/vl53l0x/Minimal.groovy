///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.tof.VL53L0XMinimal

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, VL53L0XMinimal.DEFAULT_ADDRESS)                   // open I²C bus, device address, (bus, address=0x29) → I2CConnection
try {
    def sensor = new VL53L0XMinimal(connection)                                  // Create VL53L0X driver, (connection) → VL53L0XMinimal

    50.times {
        def d = sensor.distance()                                                // Measure distance, () → int mm
        if (sensor.rangeValid()) {                                               // Check last measurement, () → boolean
            println "$d mm"
        } else {
            println "out of range"
        }
        Thread.sleep(100)
    }
} finally {
    connection.close()
}
