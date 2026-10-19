///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.DisplayConnection
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.connection.WireMode
import it.uhde.periph.chips.display.St7036Full

// ST7036 complete example: EA DOGM162W-A (2 x 16, 3.3 V) on a Raspberry Pi, every Full method: text, cursor and blink, entry mode, shift, contrast, booster, follower, bias, custom characters, display on/off.
// Wiring: SI=GPIO10 (SPI0 MOSI), SCL=GPIO11, CSB=GPIO8 (CE0), RS=GPIO25 (C/D), RESET=GPIO17; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.

fun main() {
    val dc = SysfsOutputPin(25)                                                                             // create RS (C/D) pin, (gpio=25) → OutputPin
    val res = SysfsOutputPin(17)                                                                            // create RESET pin, (gpio=17) → OutputPin
    val connection = DisplayConnection.hardware(0, 0, 1000000, dc, res, null, WireMode.SIMPLEX)             // open display connection, (bus, device, speedHz=1 MHz, dc, res, busy=null, wireMode)
    val lcd = St7036Full(connection)                                                                        // construct driver, (connection, lines=2, columns=0 default, supply=SUPPLY_3V3, contrast=-1 preset) → St7036Full

    lcd.clear()                                                                                             // clear display, ()
                                                                                                            // writes spaces to all of DDRAM, cursor to home (2 ms)
    lcd.printAt(0, 0, "Complete example")                                                                   // write text, (col, row, text)
    lcd.setCursor(0, 1)                                                                                     // move cursor, (col, row)
    lcd.write("Cursor + blink")                                                                             // write text at the cursor, (text)
    lcd.cursor(true, true)                                                                                  // cursor, (on, blink)
    Thread.sleep(2000)
    lcd.cursor(false, false)                                                                                // cursor, (on, blink)

    val heart = intArrayOf(0x00, 0x0A, 0x1F, 0x1F, 0x0E, 0x04, 0x00, 0x00)
    val arrow = intArrayOf(0x04, 0x02, 0x1F, 0x02, 0x04, 0x00, 0x00, 0x00)
    lcd.createChar(0, heart)                                                                                // define user character, (index 0..7, rows[8])
                                                                                                            // 8 rows of 5 bits into CGRAM, DDRAM address restored
    lcd.createChar(1, arrow)                                                                                // define user character, (index 0..7, rows[8])
    lcd.clear()                                                                                             // clear display, ()
    lcd.setCursor(0, 0)                                                                                     // move cursor, (col, row)
    lcd.write("I ")                                                                                         // write text at the cursor, (text)
    lcd.writeChar(0)                                                                                        // write raw character code, (code)
    lcd.write(" custom ")                                                                                   // write text at the cursor, (text)
    lcd.writeChar(1)                                                                                        // write raw character code, (code)
    Thread.sleep(2000)

    lcd.setContrast(0x08)                                                                                   // set contrast, (value 0..63)
    lcd.printAt(0, 1, "low contrast")                                                                       // write text, (col, row, text)
    Thread.sleep(1500)
    lcd.setContrast(0x30)                                                                                   // set contrast, (value 0..63)
    lcd.printAt(0, 1, "high contrast")                                                                      // write text, (col, row, text)
    Thread.sleep(1500)
    lcd.setContrast(0x18)                                                                                   // set contrast, (value 0..63)
                                                                                                            // 0x18 is the DOGM162 3.3 V preset
    lcd.setBias(false)                                                                                      // LCD bias, (quarter=false), 1/5 bias
    lcd.setFollower(true, 5)                                                                                // voltage follower, (on, ratio 0..7), 1 + Rb/Ra = 2.5
    lcd.setBooster(true)                                                                                    // voltage booster, (on)

    lcd.clear()                                                                                             // clear display, ()
    lcd.setEntryMode(true, false)                                                                           // entry mode, (increment, shift=false)
    lcd.printAt(0, 0, "Shift display ->")                                                                   // write text, (col, row, text)
    lcd.printAt(0, 1, "0123456789ABCDEF")                                                                   // write text, (col, row, text)
    Thread.sleep(1000)
    repeat(6) {
        lcd.shiftDisplay(true)                                                                                  // shift display, (right)
        Thread.sleep(300)
    }
    lcd.returnHome()                                                                                        // return home, (), undoes the display shift
    lcd.shiftCursor(true)                                                                                   // shift cursor, (right)
    lcd.setDdramAddress(0x40)                                                                               // set DDRAM address, (address)
    Thread.sleep(1000)

    lcd.display(false)                                                                                      // display on/off, (on)
    Thread.sleep(800)
    lcd.display(true)                                                                                       // display on/off, (on)
    connection.close()
}
