///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.chips.display.Pcf8576Full
import it.uhde.periph.transport.I2CTransport

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt()  ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x38
    I2CTransport(bus, addr).use { transport ->                                   // open I²C bus, (bus, address=0x38) → I2CTransport
        val lcd = Pcf8576Full(transport)                                          // construct driver, (transport) → Pcf8576Full
        lcd.clear()                                                               // blank the display, () → void
                                                                                   // zeros all 40 columns of display RAM
        lcd.deviceSelect(0)                                                       // select device on the bus, (subaddress 0–7) → void
                                                                                   // sets the subaddress counter for cascaded use
        lcd.setMode(Pcf8576Full.BACKPLANES_4, Pcf8576Full.BIAS_1_3_FULL)          // set drive mode, (backplanes 1–4, bias 0/1) → void
                                                                                   // configures 1:4 multiplex with 1/3 bias
        lcd.setBlink(Pcf8576Full.BLINK_2_HZ, false)                                // set blink frequency, (frequency 0–3, alternateBank=false) → void
                                                                                   // ~2 Hz blink for visual attention
        lcd.setBank(Pcf8576Full.BANK_0, Pcf8576Full.BANK_0)                        // select RAM bank, (inputBank 0/1, outputBank 0/1) → void
                                                                                   // selects rows 0-1 for both input and output

        val digits = intArrayOf(5, 6, 7, 8)
        val out = ByteArray(digits.size) { i -> Pcf8576Full.SEVEN_SEG[digits[i]].toByte() }   // encode 7-segment digit, (digit 0–9) → int
        lcd.writeRaw(0, out)                                                       // write raw bytes, (address 0–39, data) → void
                                                                                   // sets data pointer to 0 and writes all four digits

        lcd.disable()                                                              // disable display output, () → void
                                                                                   // blanks the panel while keeping RAM contents
        lcd.enable()                                                               // enable display output, () → void
                                                                                   // resumes output from RAM with the prior configuration
    }
}
