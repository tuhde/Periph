///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-groovy:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.led.SAA1064Full

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x38"
int addr = Integer.decode(addrStr)

I2CConnection connection = new I2CConnection(bus, addr)                     // open I²C bus, (bus, address=0x38) → I2CConnection
try {
    def display = new SAA1064Full(connection)                           // construct driver, (connection) → SAA1064Full

    // --- Four-digit counter ---
    // A fast counter from 0 to 9999, the decimal point sweeping across the four
    // positions, the segment current stepped through 3/9/15/21 mA, a switch to
    // static mode (only digits 1 and 2 stay lit), a 2 s segment test and the
    // status byte (the PR flag is set after power-up and clears on the first read).
    for (int n = 0; n < 10000; n += 37) {
        display.showNumber(n)                                           // show the counter, (value 0–9999) → void
        Thread.sleep(10)
    }
    display.showDigits([1, 2, 3, 4] as int[])                           // show four digits, (digits) → void
    for (int pos in [1, 2, 3, 4]) {
        display.setDecimalPoint(pos, true)                              // light a decimal point, (position, on) → void
        Thread.sleep(400)
        display.setDecimalPoint(pos, false)
    }
    for (int ma in [3, 9, 15, 21]) {
        display.setCurrent(ma)                                          // step the segment current, (ma) → void
        display.showNumber(8888)
        Thread.sleep(1000)
    }
    display.setCurrent(12)
    display.setMode(false)                                              // static mode, (dynamic) → void
    Thread.sleep(2000)
    display.setMode(true)
    display.segmentTest(true)                                           // segment test, (on) → void
    Thread.sleep(2000)
    display.segmentTest(false)
    println display.readStatus()                                        // read the status byte, () → int
    println display.powerResetFlag()                                    // read the PR flag, () → boolean
    display.clear()                                                     // blank all digits, () → void
} finally {
    connection.close()
}
