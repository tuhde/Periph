///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-groovy:1.5.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.led.HT16K33Full

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x70"
int addr = Integer.decode(addrStr)

I2CConnection connection = new I2CConnection(bus, addr)                         // open I²C bus, (bus, address=0x70) → I2CConnection
try {
    def matrix = new HT16K33Full(connection)                         // construct driver, (connection) → HT16K33Full

    matrix.clear()                                                   // blank the display, () → void
                                                                      // zeros the shadow RAM and writes it
    matrix.setPixel(2, 5, true)                                      // set one LED, (com 0–7, row 0–15, on) → void
    matrix.show()                                                    // write the shadow RAM, () → void
    println(matrix.getPixel(2, 5))                        // read a pixel from the shadow RAM, (com, row) → boolean
    matrix.writeRaw([(byte) 0xFF, (byte) 0x0F] as byte[], 0)                // write raw RAM bytes, (data 1–16 bytes, address 0–15) → void
                                                                      // com 0 rows 0–11 lit
    matrix.setBrightness(8)                                          // set dimming, (level 0–15) → void
                                                                      // 9/16 duty
    matrix.setBlink(1.0d)                                             // set blink frequency, (0, 0.5, 1, 2 Hz) → void
                                                                      // blinks at 1 Hz
    Thread.sleep(3000)
    matrix.setBlink(0.0d)
    matrix.displayOff()                                              // display output off, () → void
    matrix.displayOn()                                               // display output on, () → void
    matrix.setIntOutput(true, false)                                 // ROW15/INT pin as INT output, (enable, activeHigh) → void
                                                                      // active low interrupt output
    println(matrix.readKeys())                            // read and clear key data, () → long bitmask
    println(matrix.keyPressed())                          // read the INT flag, () → boolean
    println(matrix.pollInterrupt())                       // read keys if INT flag set, () → long (0 if none)
    matrix.onInterrupt({ long keys -> println(keys) } as java.util.function.LongConsumer)             // subscribe to key presses, (callback) → void
                                                                      // polls every 20 ms when no INT pin is wired
    Thread.sleep(100)
    matrix.offInterrupt()                                            // unsubscribe, () → void
    matrix.standby()                                                 // oscillator off, () → void
    matrix.wake()                                                    // oscillator on and restore state, () → void
} finally {
    connection.close()
}
