///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-groovy:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.display.PCF8578Full

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x3C"
int addr = Integer.decode(addrStr)

I2CConnection connection = new I2CConnection(bus, addr)   // open I²C bus, (bus, address=0x3C) → I2CConnection
try {
    def lcd = new PCF8578Full(connection, 16)   // construct driver, (connection, rows=16 8/16/24/32) → PCF8578Full
    // clears RAM and switches the display on in mixed mode

    lcd.clear()   // clear display RAM, () → void
    // zeroes all 4 banks x 40 columns and the shadow
    lcd.setPixel(1, 1, true)   // set one pixel, (x 0–23, y 0–15, on) → void
    // writes a single RAM byte from the shadow
    lcd.writeColumns(4, 0, [0xFF, 0x81, 0xFF] as byte[])   // write column bytes, (x 0–23, bank 0–1, data bytes) → void
    // character access mode, bit 0 = top row of the bank
    boolean on = lcd.getPixel(1, 1)   // read pixel from shadow, (x, y) → boolean
    // no bus traffic
    lcd.fill(true)   // fill display, (on) → void
    // every displayed pixel on
    lcd.fill(false)   // fill display, (on) → void
    // every displayed pixel off
    lcd.drawLine(0, 0, 23, 15, true)   // draw line, (x0, y0, x1, y1, on) → void
    // Bresenham, written as one batch of changed bytes
    lcd.drawRect(0, 0, 24, 16, true, false)   // draw rectangle outline, (x, y, w, h, on, fill) → void
    // border around the whole panel
    lcd.drawRect(8, 4, 8, 8, true, true)   // draw filled rectangle, (x, y, w, h, on, fill) → void
    // solid 8x8 block in the middle
    lcd.showBitmap(2, 8, [[0x18, 0x24, 0x42, 0x81] as byte[]] as byte[][])   // blit bitmap, (x, y multiple of 8, bands of column bytes) → void
    // one 8-pixel-high band at the lower bank
    lcd.setDisplay(PCF8578Full.DISPLAY_INVERSE)   // set display status, (status 0 blank/1 normal/2 all on/3 inverse) → void
    // inverse video; RAM is preserved
    lcd.setDisplay(PCF8578Full.DISPLAY_NORMAL)   // set display status, (status 0–3) → void
    // back to normal video
    lcd.setMode(24, true)   // set multiplex rate, (rows 8/16/24/32, mixed) → void
    // 1:24 drive; mixed mode keeps RAM accessible
    lcd.setMode(16, true)   // set multiplex rate, (rows 8/16/24/32, mixed) → void
    // back to 1:16
    lcd.setStartBank(1)   // set start bank, (bank 0–3) → void
    // shows RAM bank 1 at the top (hardware scroll)
    lcd.scroll(-1)   // scroll by banks, (banks int) → void
    // adds -1 to the start bank modulo banks in use
    lcd.writeRam(16, 0, [0xAA, 0x55] as byte[], PCF8578Full.ACCESS_CHARACTER)   // write raw RAM, (x 0–39, bank 0–3, data bytes, mode) → void
    // character order at RAM column 16
    byte[] data = lcd.readRam(16, 0, 2, PCF8578Full.ACCESS_CHARACTER)   // read raw RAM, (x 0–39, bank 0–3, n, mode) → byte[]
    // address-set commands, repeated start, then a read
    lcd.deviceSelect(0)   // select device subaddress, (subaddress 0–15) → void
    // 0 addresses this PCF8578; others target cascaded PCF8579s
    lcd.reset()   // re-initialise, () → void
    // clears RAM and restores normal video
} finally {
    connection.close()
}
