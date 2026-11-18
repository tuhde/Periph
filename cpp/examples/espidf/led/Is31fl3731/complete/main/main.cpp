// Auto-generated ESP-IDF example for Is31fl3731 (Complete).
// Mirrors the Arduino IS31FL3731_Complete example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Is31fl3731.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x74;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Is31fl3731Full matrix(connection);                    // Create IS31FL3731 driver, (connection)

    matrix.clear();                                       // Switch all LEDs of the drawing frame off, () → void
    matrix.set_pixel(2, 5, 200);                          // Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → bool
    printf("%d\n", (int)(matrix.get_pixel(2, 5)));        // Read a pixel from the shadow copy, (row, col) → uint8_t 0–255
    uint8_t ramp[144];
    for (int i = 0; i < 144; i++) ramp[i] = (uint8_t)i;
    matrix.set_pixels(ramp, 144);                         // Write a whole frame, (data, len must be 144) → bool
    matrix.fill(40);                                      // Set every LED's PWM brightness, (brightness 0–255) → void
    matrix.set_led_enabled(0, 0, false);                  // LED-control (on/off) bit, (row, col, on) → bool
    matrix.set_led_enabled(0, 0, true);
    matrix.set_blink(1, 1, true);                         // Blink-enable bit of one LED, (row, col, on) → bool
    matrix.set_blink_all(false);                          // Blink-enable bit of every LED, (on) → void
    matrix.enable_blink(true, 2);                         // Blink on, (enabled, period 0–7 × 0.27 s) → bool
                                                          // 0.54 s period, 50 % duty
    vTaskDelay(pdMS_TO_TICKS(2000));
    matrix.enable_blink(false);
    matrix.select_frame(1);                               // Frame subsequent drawing writes to, (frame 0–7) → bool
    matrix.fill(100);
    matrix.copy_frame(1, 2);                              // Copy PWM values between frames, (src, dst) → bool
    matrix.clear_frame(2);                                // Re-initialise one frame, (frame 0–7) → bool
    matrix.clear_all_frames();                            // Re-initialise all eight frames, () → void
    matrix.select_frame(0);
    matrix.show_frame(0);                                 // Display a frame in Picture mode, (frame 0–7) → bool
    matrix.set_picture_mode();                            // Picture mode, () → void
    matrix.start_auto_play(2, 1, 110, 0);                 // Auto Frame Play, (frames 0–7, loops 0–7, delay_ms, start_frame) → bool
                                                          // frames/loops 0 = all/endless; plays 2 frames once
    vTaskDelay(pdMS_TO_TICKS(1000));
    printf("%d\n", (int)(matrix.frame_state()));          // Read FRAME_STATE, () → uint8_t
    printf("%d\n", (int)(matrix.current_frame()));        // Frame being displayed, () → uint8_t 0–7
    printf("%d\n", (int)(matrix.movie_finished()));       // Movie-finished flag (clears it), () → bool
    matrix.stop_auto_play();                              // Back to Picture mode on the current frame, () → void
    matrix.set_breath(true, 3, 3, 2);                     // Breath function, (enabled, fade_in, fade_out, extinguish 0–7) → bool
    vTaskDelay(pdMS_TO_TICKS(3000));
    matrix.set_breath(false);
    matrix.set_intensity_control(false);                  // Share frame 1's PWM with all frames, (use_frame1) → void
    matrix.set_audio_gain(6, true, false);                // Audio gain, (gain_db 0–21 step 3, agc, fast) → bool
    matrix.set_audio_rate(10);                            // Audio ADC interval, (a 0–255, × 46 µs) → void
    matrix.set_audio_sync(false);                         // Audio intensity modulation, (enabled) → void
    matrix.set_audio_mode(false);                         // Audio Frame Play on/off, (enabled) → void
    printf("%d\n", (int)(matrix.read_function_register(0x03)));// Raw function-page read, (reg 0–0x0C) → uint8_t
    matrix.write_function_register(0x03, 0x0B);           // Raw function-page write, (reg, value) → bool
    printf("%d\n", (int)(matrix.poll_interrupt()));       // FRAME_STATE, clears INT, () → uint8_t
    matrix.on_interrupt([](uint8_t status) { (void)status; });// Callback when a movie ends, (callback, intPin) → void
    matrix.service();                                     // Poll once and dispatch, () → void
    matrix.off_interrupt();                               // Unsubscribe, () → void
    matrix.shutdown();                                    // Software shutdown, (on = true) → void
    matrix.shutdown(false);                               // Back to normal operation

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
