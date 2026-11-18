///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-kotlin:1.5.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.led.Is31fl3731Full
import it.uhde.periph.chips.led.Is31fl3731Minimal

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt()  ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x74
    I2CConnection(bus, addr).use { connection ->                       // open I²C bus, (bus, address=0x74) → I2CConnection
        // --- Animated matrix: auto-play movie, blinking checkerboard, breath, gamma fade ---
        // A diagonal moves across frames 1-8 and is played by the chip itself, then a
        // blinking checkerboard is shown in Picture mode, a filled frame breathes, the
        // matrix fades through the 64-step gamma table, a finite 2-loop movie ends (polled
        // with movieFinished) and the display is shut down.

        val matrix = Is31fl3731Full(connection)                     // construct driver, (connection) → Is31fl3731Full

        matrix.clearAllFrames()                                         // initialise all eight frames, () → void
        for (f in 0 until 8) {
            matrix.selectFrame(f)                                       // draw on frame f, (frame 0–7) → void
            for (r in 0 until Is31fl3731Minimal.ROWS) {
                matrix.setPixel(r, (f + r) % 16, 255)                   // one diagonal LED per row, (row, col, brightness) → void
            }
        }
        matrix.selectFrame(0)
        matrix.startAutoPlay(0, 0, 100, 0)                              // endless 8-frame movie, (frames, loops, delayMs, startFrame) → void
        for (i in 0 until 6) {
            println(matrix.currentFrame())                              // frame being displayed, () → int
            Thread.sleep(500)
        }
        matrix.stopAutoPlay()                                           // Picture mode, current frame kept, () → void
        matrix.selectFrame(0)
        matrix.clear()                                                  // blank the drawing frame, () → void
        val board = ByteArray(144)
        for (r in 0 until 9)
            for (c in 0 until 16) board[r * 16 + c] = if ((r + c) % 2 == 0) 255.toByte() else 0
        matrix.setPixels(board)                                         // checkerboard, (data 144 bytes) → void
        matrix.setBlinkAll(true)                                        // every LED blinks, (on) → void
        matrix.enableBlink(true, 2)                                     // blink period 2 × 0.27 s, (enabled, period) → void
        matrix.showFrame(0)                                             // show frame 1 in Picture mode, (frame) → void
        Thread.sleep(3000)
        matrix.enableBlink(false, 1)
        matrix.setBlinkAll(false)
        matrix.fill(255)                                                // full brightness, (brightness) → void
        matrix.setBreath(true, 4, 4, 2)                                 // breathing, (enabled, fadeIn, fadeOut, extinguish) → void
        Thread.sleep(5000)
        matrix.setBreath(false, 0, 0, 0)
        for (level in Is31fl3731Full.GAMMA_64) {
            matrix.fill(level)                                          // gamma-corrected fade, (brightness) → void
            Thread.sleep(30)
        }
        matrix.setPictureMode()                                         // Picture mode, () → void
        matrix.startAutoPlay(0, 2, 100, 0)                              // two loops, (frames, loops, delayMs, startFrame) → void
        for (i in 0 until 30) {
            Thread.sleep(100)
            if (matrix.movieFinished()) break                           // poll the movie-finished flag, () → boolean
        }
        matrix.clearAllFrames()
        matrix.shutdown(true)                                           // display off, (on) → void
    }
}
