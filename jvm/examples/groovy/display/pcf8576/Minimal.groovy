///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.display.Pcf8576Minimal

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x38"
int addr = Integer.decode(addrStr)

I2CConnection connection = new I2CConnection(bus, addr)                         // open I²C bus, (bus, address=0x38) → I2CConnection
try {
    def lcd = new Pcf8576Minimal(connection)                                    // construct driver, (connection) → Pcf8576Minimal

    int[] digits = [1, 2, 3, 4]
    for (int i = 0; i < digits.length; i++) {
        int seg = Pcf8576Minimal.SEVEN_SEG[digits[i]]                          // encode 7-segment digit, (digit 0–9) → int
        lcd.setDigit7seg(i, seg)                                                // write one digit, (position 0–19, segments 0–255) → void
    }
} finally {
    connection.close()
}
