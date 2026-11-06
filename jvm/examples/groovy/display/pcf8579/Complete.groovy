///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-groovy:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.display.PCF8579Full

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x3C"
int addr = Integer.decode(addrStr)

I2CConnection connection = new I2CConnection(bus, addr)   // open I²C bus, (bus, address=0x3C) → I2CConnection
try {
    def lcd = new PCF8579Full(connection, 0, 16)   // construct driver, (connection, subaddress=0 0–15, rows=16 8/16/24/32, mixed=false) → PCF8579Full
    // clears this device's RAM and switches the display on

    lcd.clear()   // clear display RAM, () → void
    // zeroes all 4 banks x 40 columns and the shadow
    lcd.setPixel(1, 1, true)   // set one pixel, (x 0–39, y 0–15, on) → void
    // writes a single RAM byte from the shadow
    lcd.writeColumns(4, 0, [0xFF, 0x81, 0xFF] as byte[])   // write column bytes, (x 0–39, bank 0–1, data bytes) → void
    // character access mode, bit 0 = top row of the bank
    boolean on = lcd.getPixel(1, 1)   // read pixel from shadow, (x, y) → boolean
    // no bus traffic
    lcd.fill(true)   // fill display, (on) → void
    // every displayed pixel on
    lcd.fill(false)   // fill display, (on) → void
    // every displayed pixel off
    lcd.drawLine(0, 0, 39, 15, true)   // draw line, (x0, y0, x1, y1, on) → void
    // Bresenham, written as one batch of changed bytes
    lcd.drawRect(0, 0, 40, 16, true, false)   // draw rectangle outline, (x, y, w, h, on, fill) → void
    // border around the whole panel
    lcd.drawRect(8, 4, 8, 8, true, true)   // draw filled rectangle, (x, y, w, h, on, fill) → void
    // solid 8x8 block in the middle
    lcd.showBitmap(2, 8, [0x18, 0x24, 0x42, 0x81] as byte[])   // blit bitmap, (x, y multiple of 8, columns bytes) → void
    // one 8-pixel-high band at the lower bank
    lcd.setDisplay(PCF8579Full.DISPLAY_INVERSE)   // set display status, (status 0 blank/1 normal/2 all on/3 inverse) → void
    // inverse video; RAM is preserved
    lcd.setDisplay(PCF8579Full.DISPLAY_NORMAL)   // set display status, (status 0–3) → void
    // back to normal video
    lcd.setMode(24, false)   // set multiplex rate, (rows 8/16/24/32, mixed=false) → void
    // 1:24 drive; mixed is the PCF8578 mode bit T
    lcd.setMode(16, false)   // set multiplex rate, (rows 8/16/24/32, mixed=false) → void
    // back to 1:16
    lcd.setStartBank(1)   // set start bank, (bank 0–3) → void
    // shows RAM bank 1 at the top (hardware scroll)
    lcd.scroll(-1)   // scroll by banks, (banks int) → void
    // adds -1 to the start bank modulo banks in use
    lcd.writeRam(16, 0, [0xAA, 0x55] as byte[], PCF8579Full.ACCESS_CHARACTER)   // write raw RAM, (x 0–39, bank 0–3, data bytes, mode) → void
    // character order at RAM column 16
    byte[] data = lcd.readRam(16, 0, 2, PCF8579Full.ACCESS_CHARACTER)   // read raw RAM, (x 0–39, bank 0–3, n, mode) → byte[]
    // address-set commands, repeated start, then a read
    lcd.writeRamCascade(new byte[160], PCF8579Full.ACCESS_FULL_GRAPHIC)   // write across devices, (data bytes, mode=2) → void
    // runs on into the following subaddresses after column 39
    lcd.reset()   // re-initialise, () → void
    // clears RAM and restores normal video
} finally {
    connection.close()
}
