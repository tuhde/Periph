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
    // --- Initialise a 16 x 40 column driver ---
    // 1:16 multiplex gives 16 rows and 40 columns; the constructor clears the RAM
    // before switching the display on so no random start-up pattern is shown.
    def lcd = new PCF8579Full(connection, 0, 16)   // construct driver, (connection, subaddress=0 0–15, rows=16 8/16/24/32, mixed=false) → PCF8579Full

    // --- Draw a border and both diagonals ---
    // Lines and rectangles are rendered in the shadow buffer and sent as one
    // batch of changed bytes per run of columns.
    lcd.drawRect(0, 0, 40, 16, true, false)   // draw rectangle outline, (x, y, w, h, on, fill) → void
    lcd.drawLine(0, 0, 39, 15, true)   // draw line, (x0, y0, x1, y1, on) → void
    lcd.drawLine(0, 15, 39, 0, true)   // draw line, (x0, y0, x1, y1, on) → void

    // --- Flash the display twice with inverse video ---
    // Inverse video only changes how the RAM is shown, so the picture is kept.
    for (int i = 0; i < 2; i++) {
        lcd.setDisplay(PCF8579Full.DISPLAY_INVERSE)   // set display status, (status 0–3) → void
        Thread.sleep(400L)
        lcd.setDisplay(PCF8579Full.DISPLAY_NORMAL)   // set display status, (status 0–3) → void
        Thread.sleep(400L)
    }

    // --- Fill all four RAM banks with distinct patterns ---
    // At 1:16 only banks 0 and 1 are visible; banks 2 and 3 act as off-screen
    // buffers that the start bank can scroll into view.
    int[] patterns = [0x55, 0x33, 0x0F, 0xFF]
    for (int bank = 0; bank < 4; bank++) {
        byte[] col = new byte[40]
        Arrays.fill(col, (byte) patterns[bank])
        lcd.writeRam(0, bank, col, PCF8579Full.ACCESS_CHARACTER)   // write raw RAM, (x 0–39, bank 0–3, data bytes, mode) → void
    }

    // --- Scroll through the banks with the hardware start bank ---
    // Stepping the start bank scrolls the display by 8 rows without rewriting RAM.
    for (int bank = 0; bank < 4; bank++) {
        lcd.setStartBank(bank)   // set start bank, (bank 0–3) → void
        byte[] first = lcd.readRam(0, bank, 1, PCF8579Full.ACCESS_CHARACTER)   // read raw RAM, (x 0–39, bank 0–3, n, mode) → byte[]
        println "start bank ${bank} first column byte 0x${String.format('%02X', first[0])}"
        Thread.sleep(1000L)
    }

    // --- Read back the first 8 RAM columns of bank 0 ---
    // Writing a counting pattern and reading it back verifies the bus path.
    lcd.setStartBank(0)   // set start bank, (bank 0–3) → void
    lcd.writeRam(0, 0, [1, 2, 3, 4, 5, 6, 7, 8] as byte[], PCF8579Full.ACCESS_CHARACTER)   // write raw RAM, (x 0–39, bank 0–3, data bytes, mode) → void
    println Arrays.toString(lcd.readRam(0, 0, 8, PCF8579Full.ACCESS_CHARACTER))   // read raw RAM, (x 0–39, bank 0–3, n, mode) → byte[]
} finally {
    connection.close()
}
