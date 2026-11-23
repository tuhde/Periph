///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-groovy:1.5.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.led.Is31fl3731Full

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x74"
int addr = Integer.decode(addrStr)

I2CConnection connection = new I2CConnection(bus, addr)            // open I²C bus, (bus, address=0x74) → I2CConnection
try {
    // --- Animated matrix: auto-play movie, blinking checkerboard, breath, gamma fade ---
    // A diagonal moves across frames 1-8 and is played by the chip itself, then a
    // blinking checkerboard is shown in Picture mode, a filled frame breathes, the
    // matrix fades through the 64-step gamma table, a finite 2-loop movie ends (polled
    // with movieFinished) and the display is shut down.

    def matrix = new Is31fl3731Full(connection)                     // construct driver, (connection) → Is31fl3731Full

    matrix.clearAllFrames()                                             // initialise all eight frames, () → void
    for (int f = 0; f < 8; f++) {
        matrix.selectFrame(f)                                           // draw on frame f, (frame 0–7) → void
        for (int r = 0; r < Is31fl3731Full.ROWS; r++) {
            matrix.setPixel(r, (f + r) % 16, 255)                       // one diagonal LED per row, (row, col, brightness) → void
        }
    }
    matrix.selectFrame(0)
    matrix.startAutoPlay(0, 0, 100, 0)                                  // endless 8-frame movie, (frames, loops, delayMs, startFrame) → void
    for (int i = 0; i < 6; i++) {
        println(matrix.currentFrame())                                  // frame being displayed, () → int
        Thread.sleep(500)
    }
    matrix.stopAutoPlay()                                               // Picture mode, current frame kept, () → void
    matrix.selectFrame(0)
    matrix.clear()                                                      // blank the drawing frame, () → void
    byte[] board = new byte[144]
    for (int r = 0; r < 9; r++)
        for (int c = 0; c < 16; c++) board[r * 16 + c] = (byte) (((r + c) % 2 == 0) ? 255 : 0)
    matrix.setPixels(board)                                             // checkerboard, (data 144 bytes) → void
    matrix.setBlinkAll(true)                                            // every LED blinks, (on) → void
    matrix.enableBlink(true, 2)                                         // blink period 2 × 0.27 s, (enabled, period) → void
    matrix.showFrame(0)                                                 // show frame 1 in Picture mode, (frame) → void
    Thread.sleep(3000)
    matrix.enableBlink(false, 1)
    matrix.setBlinkAll(false)
    matrix.fill(255)                                                    // full brightness, (brightness) → void
    matrix.setBreath(true, 4, 4, 2)                                     // breathing, (enabled, fadeIn, fadeOut, extinguish) → void
    Thread.sleep(5000)
    matrix.setBreath(false, 0, 0, 0)
    for (int level : Is31fl3731Full.GAMMA_64) {
        matrix.fill(level)                                              // gamma-corrected fade, (brightness) → void
        Thread.sleep(30)
    }
    matrix.setPictureMode()                                             // Picture mode, () → void
    matrix.startAutoPlay(0, 2, 100, 0)                                  // two loops, (frames, loops, delayMs, startFrame) → void
    for (int i = 0; i < 30; i++) {
        Thread.sleep(100)
        if (matrix.movieFinished()) break                               // poll the movie-finished flag, () → boolean
    }
    matrix.clearAllFrames()
    matrix.shutdown(true)                                               // display off, (on) → void
} finally {
    connection.close()
}
