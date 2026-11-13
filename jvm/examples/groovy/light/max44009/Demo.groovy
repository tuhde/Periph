///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.MAX44009Full

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, MAX44009Full.DEFAULT_ADDRESS)                              // open I²C bus, device address, (bus, address=0x4A) → I2CConnection
try {
    def sensor = new MAX44009Full(connection)                                                      // construct driver, (connection) → MAX44009Full
    def backlight = { double lx -> 10 + 90 * (Math.log10(Math.min(10000.0d, Math.max(0.1d, lx))) + 1) / 5 }

    println("manual ${sensor.config.manual}")                                                      // Read CONFIG, () → Config
    double lux = sensor.lux()                                                                      // Read illuminance, () → double lux
    println("lux ${lux}, resolution ${sensor.resolution()} lx/LSB")                                // Lux per LSB, () → double lux

    // Arm a window of +-30 % around the current illuminance; the callback only records the event.
    def pending = new java.util.concurrent.atomic.AtomicInteger()
    sensor.setThresholdDelay(2.0d)                                                                 // Set the threshold delay, (seconds) → void
    sensor.setThresholds(lux * 0.7d, lux * 1.3d)                                                   // Set the interrupt window, (lowLux, highLux) → void
    sensor.enableInterrupt()                                                                       // Enable the interrupt, () → void
    sensor.onInterrupt { int status -> pending.set(status) }                                       // Subscribe, (callback(status)) → void

    // Run for 60 s: react to interrupts, print the raw reading once a second.
    for (int s = 0; s < 60; s++) {
        if (pending.getAndSet(0) != 0) {
            double now = sensor.lux()                                                              // Read illuminance, () → double lux
            println("lux ${now} -> backlight ${(int) backlight(now)} %")
            sensor.setThresholds(now * 0.7d, now * 1.3d)                                           // Set the interrupt window, (lowLux, highLux) → void
        }
        def raw = sensor.readRaw()                                                                 // Read exponent and mantissa, () → Raw
        println("E=${raw.exponent} M=${raw.mantissa} overrange=${sensor.isOverrange()}")
        Thread.sleep(1000)
    }
    sensor.disableInterrupt()                                                                      // Disable the interrupt, () → void
    sensor.offInterrupt()                                                                          // Unsubscribe, () → void
} finally {
    connection.close()
}
