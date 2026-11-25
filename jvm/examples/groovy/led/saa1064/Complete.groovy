///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.led.SAA1064Full

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x38"
int addr = Integer.decode(addrStr)

I2CConnection connection = new I2CConnection(bus, addr)                     // open I²C bus, (bus, address=0x38) → I2CConnection
try {
    def display = new SAA1064Full(connection)                           // construct driver, (connection) → SAA1064Full
    display.showDigits([1, 2, 3, 4] as int[])                           // show four digits, (digits 4 × 0–9 or BLANK) → void
                                                                        // default mapping a=bit0 … g=bit6, dp=bit7
    Thread.sleep(1000)
    display.showNumber(42)                                              // show a number, (value 0–9999) → void
                                                                        // leading zeros are blanked
    display.writeRaw([0x7F, 0x7F] as int[], 3)                          // write raw segment bytes, (patterns 1–4, firstDigit 1–4) → void
                                                                        // digits 3 and 4 show 8
    Thread.sleep(1000)
    display.showDigit(2, 7, true)                                       // show one digit, (position 1–4, value 0–9 or BLANK, dp) → void
                                                                        // digit 2 = 7 with decimal point
    display.setDecimalPoint(2, false)                                   // switch a decimal point, (position 1–4, on) → void
                                                                        // keeps the cached segments
    display.setSegmentMap([a: 0, b: 1, c: 2, d: 3, e: 4, f: 5, g: 6, dp: 7])// remap segment bits, (mapping a–g, dp → bit 0–7) → void
    display.setCurrent(21)                                              // set segment current, (ma 0, 3, 6, 9, 12, 15, 18, 21) → void
                                                                        // C4–C6
    display.setMode(false)                                              // select static/dynamic mode, (dynamic) → void
                                                                        // static shows only digits 1 and 2
    Thread.sleep(1000)
    display.setMode(true)
    display.blank(true, false)                                          // blank digit pairs, (digits13, digits24) → void
                                                                        // digits 1+3 off
    Thread.sleep(1000)
    display.blank(false, false)
    display.segmentTest(true)                                           // segment test, (on) → void
                                                                        // lights every segment
    Thread.sleep(1000)
    display.segmentTest(false)
    println display.readStatus()                                        // read the status byte, () → int
    println display.powerResetFlag()                                    // read the PR flag, () → boolean
                                                                        // set after power-up, clears when read
    display.writeControl(0x47)                                          // write the raw control byte, (value 0–127) → void
                                                                        // dynamic mode, unblanked, 12 mA
    display.clear()                                                     // blank all digits, () → void
} finally {
    connection.close()
}
