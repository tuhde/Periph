///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.chips.display.Pcf8576Full
import it.uhde.periph.transport.I2CTransport

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x38"
int addr = Integer.decode(addrStr)

I2CTransport transport = new I2CTransport(bus, addr)                          // open I²C bus, (bus, address=0x38) → I2CTransport
try {
    def lcd = new Pcf8576Full(transport)                                        // construct driver, (transport) → Pcf8576Full
    lcd.clear()                                                                 // blank the display, () → void
                                                                                 // zeros all 40 columns of display RAM
    lcd.deviceSelect(0)                                                         // select device on the bus, (subaddress 0–7) → void
                                                                                 // sets the subaddress counter for cascaded use
    lcd.setMode(Pcf8576Full.BACKPLANES_4, Pcf8576Full.BIAS_1_3_FULL)            // set drive mode, (backplanes 1–4, bias 0/1) → void
                                                                                 // configures 1:4 multiplex with 1/3 bias
    lcd.setBlink(Pcf8576Full.BLINK_2_HZ, false)                                 // set blink frequency, (frequency 0–3, alternateBank=false) → void
                                                                                 // ~2 Hz blink for visual attention
    lcd.setBank(Pcf8576Full.BANK_0, Pcf8576Full.BANK_0)                         // select RAM bank, (inputBank 0/1, outputBank 0/1) → void
                                                                                 // selects rows 0-1 for both input and output

    int[] digits = [5, 6, 7, 8]
    byte[] out = new byte[digits.length]
    for (int i = 0; i < digits.length; i++) {
        out[i] = (byte) Pcf8576Full.SEVEN_SEG[digits[i]]                        // encode 7-segment digit, (digit 0–9) → int
    }
    lcd.writeRaw(0, out)                                                         // write raw bytes, (address 0–39, data) → void
                                                                                 // sets data pointer to 0 and writes all four digits

    lcd.disable()                                                                // disable display output, () → void
                                                                                 // blanks the panel while keeping RAM contents
    lcd.enable()                                                                 // enable display output, () → void
                                                                                 // resumes output from RAM with the prior configuration
} finally {
    transport.close()
}
