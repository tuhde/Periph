#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x74
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();                                    // other cores: board's default SDA/SCL
    Wire.setClock(400000);
#endif
    I2CConnection connection(Wire, TEST_ADDR);

    // --- Animated matrix: auto-play movie, blinking checkerboard, breath, gamma fade ---
    // A diagonal moves across frames 1-8 and is played by the chip itself, then a
    // blinking checkerboard is shown in Picture mode, a filled frame breathes, the
    // matrix fades through the 64-step gamma table, a finite 2-loop movie ends (polled
    // with movie_finished) and the display is shut down.
    Is31fl3731Full matrix(connection);                    // Create IS31FL3731 driver, (connection)

    matrix.clear_all_frames();                            // Initialise all eight frames, () → void
    for (uint8_t f = 0; f < 8; f++) {
        matrix.select_frame(f);                           // Draw on frame f, (frame 0–7) → bool
        for (uint8_t r = 0; r < 9; r++) {
            matrix.set_pixel(r, (f + r) % 16, 255);       // One diagonal LED per row, (row, col, brightness) → bool
        }
    }
    matrix.select_frame(0);
    matrix.start_auto_play(0, 0, 100, 0);                 // Endless 8-frame movie, (frames, loops, delay_ms, start_frame) → bool
    for (int i = 0; i < 6; i++) {
        Serial.println(matrix.current_frame());           // Frame being displayed, () → uint8_t
        delay(500);
    }
    matrix.stop_auto_play();                              // Picture mode, current frame kept, () → void
    matrix.select_frame(0);
    matrix.clear();                                       // Blank the drawing frame, () → void
    uint8_t board[144];
    for (int r = 0; r < 9; r++)
        for (int c = 0; c < 16; c++) board[r * 16 + c] = ((r + c) % 2 == 0) ? 255 : 0;
    matrix.set_pixels(board, 144);                        // Checkerboard, (data, len 144) → bool
    matrix.set_blink_all(true);                           // Every LED blinks, (on) → void
    matrix.enable_blink(true, 2);                         // Blink period 2 × 0.27 s, (enabled, period) → bool
    matrix.show_frame(0);                                 // Show frame 1 in Picture mode, (frame) → bool
    delay(3000);
    matrix.enable_blink(false);
    matrix.set_blink_all(false);
    matrix.fill(255);                                     // Full brightness, (brightness) → void
    matrix.set_breath(true, 4, 4, 2);                     // Breathing, (enabled, fade_in, fade_out, extinguish) → bool
    delay(5000);
    matrix.set_breath(false);
    for (uint8_t i = 0; i < 64; i++) {
        matrix.fill(Is31fl3731Full::GAMMA_64[i]);         // Gamma-corrected fade, (brightness) → void
        delay(30);
    }
    matrix.set_picture_mode();                            // Picture mode, () → void
    matrix.start_auto_play(0, 2, 100, 0);                 // Two loops, (frames, loops, delay_ms, start_frame) → bool
    for (int i = 0; i < 30 && !matrix.movie_finished(); i++) {
        delay(100);
    }
    Serial.println(matrix.movie_finished());              // Flag was cleared by the poll above, () → bool
    matrix.clear_all_frames();
    matrix.shutdown();                                    // Display off, (on = true) → void

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
