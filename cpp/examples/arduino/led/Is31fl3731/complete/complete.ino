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
    Is31fl3731Full matrix(connection);                    // Create IS31FL3731 driver, (connection)

    matrix.clear();                                       // Switch all LEDs of the drawing frame off, () → void
    matrix.set_pixel(2, 5, 200);                          // Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → bool
    Serial.println(matrix.get_pixel(2, 5));               // Read a pixel from the shadow copy, (row, col) → uint8_t 0–255
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
    delay(2000);
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
    delay(1000);
    Serial.println(matrix.frame_state());                 // Read FRAME_STATE, () → uint8_t
    Serial.println(matrix.current_frame());               // Frame being displayed, () → uint8_t 0–7
    Serial.println(matrix.movie_finished());              // Movie-finished flag (clears it), () → bool
    matrix.stop_auto_play();                              // Back to Picture mode on the current frame, () → void
    matrix.set_breath(true, 3, 3, 2);                     // Breath function, (enabled, fade_in, fade_out, extinguish 0–7) → bool
    delay(3000);
    matrix.set_breath(false);
    matrix.set_intensity_control(false);                  // Share frame 1's PWM with all frames, (use_frame1) → void
    matrix.set_audio_gain(6, true, false);                // Audio gain, (gain_db 0–21 step 3, agc, fast) → bool
    matrix.set_audio_rate(10);                            // Audio ADC interval, (a 0–255, × 46 µs) → void
    matrix.set_audio_sync(false);                         // Audio intensity modulation, (enabled) → void
    matrix.set_audio_mode(false);                         // Audio Frame Play on/off, (enabled) → void
    Serial.println(matrix.read_function_register(0x03));  // Raw function-page read, (reg 0–0x0C) → uint8_t
    matrix.write_function_register(0x03, 0x0B);           // Raw function-page write, (reg, value) → bool
    Serial.println(matrix.poll_interrupt());              // FRAME_STATE, clears INT, () → uint8_t
    matrix.on_interrupt([](uint8_t status) { (void)status; });// Callback when a movie ends, (callback, intPin) → void
    matrix.service();                                     // Poll once and dispatch, () → void
    matrix.off_interrupt();                               // Unsubscribe, () → void
    matrix.shutdown();                                    // Software shutdown, (on = true) → void
    matrix.shutdown(false);                               // Back to normal operation

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
