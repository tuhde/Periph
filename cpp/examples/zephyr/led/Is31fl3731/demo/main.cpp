#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Is31fl3731.h"

#ifndef IS31FL3731_I2C_NODE
#define IS31FL3731_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef IS31FL3731_ADDR
#define IS31FL3731_ADDR 0x74
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(IS31FL3731_I2C_NODE);
    I2CConnectionZephyr connection(dev, IS31FL3731_ADDR);

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
        printk("%d\n", (int)(matrix.current_frame()));    // Frame being displayed, () → uint8_t
        k_msleep(500);
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
    k_msleep(3000);
    matrix.enable_blink(false);
    matrix.set_blink_all(false);
    matrix.fill(255);                                     // Full brightness, (brightness) → void
    matrix.set_breath(true, 4, 4, 2);                     // Breathing, (enabled, fade_in, fade_out, extinguish) → bool
    k_msleep(5000);
    matrix.set_breath(false);
    for (uint8_t i = 0; i < 64; i++) {
        matrix.fill(Is31fl3731Full::GAMMA_64[i]);         // Gamma-corrected fade, (brightness) → void
        k_msleep(30);
    }
    matrix.set_picture_mode();                            // Picture mode, () → void
    matrix.start_auto_play(0, 2, 100, 0);                 // Two loops, (frames, loops, delay_ms, start_frame) → bool
    for (int i = 0; i < 30 && !matrix.movie_finished(); i++) {
        k_msleep(100);
    }
    printk("%d\n", (int)(matrix.movie_finished()));       // Flag was cleared by the poll above, () → bool
    matrix.clear_all_frames();
    matrix.shutdown();                                    // Display off, (on = true) → void

    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
