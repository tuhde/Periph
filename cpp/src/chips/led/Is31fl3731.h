#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/InputPin.h"

/** @brief IS31FL3731 144-LED matrix driver (CharliePlex) — minimal interface.
 *
 *  Lights individual LEDs of the 9 x 16 matrix with 8-bit PWM in Picture mode,
 *  drawing into frame 1. I2C address 0x74-0x77 (AD pin).
 *
 *  Defaults baked in at construction: software shutdown while configuring,
 *  Picture mode showing frame 1, frame 1 initialised (all LED-control bits on,
 *  PWM and blink cleared), then normal operation; LED current set by R_EXT
 *  (3.2 mA at 20 kOhm). The driver keeps a shadow copy of every frame (the
 *  chip only allows frame register reads in software shutdown) and splits
 *  block writes into 16-byte chunks so they fit Arduino Wire's buffer.
 *
 *  @param connection RegisterConnection (I2C) pointing at the device.
 */
class Is31fl3731Minimal {
public:
    static constexpr uint8_t ROWS = 9;
    static constexpr uint8_t COLS = 16;

    explicit Is31fl3731Minimal(RegisterConnection& connection);

    /** @brief Set the PWM brightness of one LED in the frame being drawn.
     *  @param row        Common line, 0-8.
     *  @param col        Sink line, 0-15.
     *  @param brightness PWM value, 0-255.
     *  @return false (and no change) if row or col is out of range.
     */
    bool set_pixel(uint8_t row, uint8_t col, uint8_t brightness);

    /** @brief Set all 144 PWM values of the frame being drawn to 0. */
    void clear();

    /** @brief Set all 144 PWM values of the frame being drawn.
     *  @param brightness PWM value, 0-255.
     */
    void fill(uint8_t brightness);

protected:
    RegisterConnection& _connection;
    int16_t _page;
    uint8_t _frame;
    uint8_t _ic;
    uint8_t _be;
    uint8_t _bpt;
    uint8_t _pwm[8][144];
    uint8_t _ctrl[8][18];
    uint8_t _blink[8][18];

    void _select(uint8_t page);
    void _write_block(uint8_t reg, const uint8_t* data, size_t len);
    void _func_write(uint8_t reg, const uint8_t* data, size_t len);
    void _func_write(uint8_t reg, uint8_t value);
    uint8_t _func_read(uint8_t reg);
    void _init_frame(uint8_t frame);
};

/** @brief IS31FL3731 full interface — frames, auto-play, blink, breath, audio, shutdown, INTB.
 *
 *  INTB delivery uses the InputPin passed to on_interrupt() (or connection.intPin());
 *  without one, call service() periodically.
 *
 *  @param connection RegisterConnection (I2C) pointing at the device.
 */
class Is31fl3731Full : public Is31fl3731Minimal {
public:
    /** @brief 32-step gamma table for 256 PWM steps (datasheet Table 17). */
    static constexpr uint8_t GAMMA_32[32] = {
        0, 1, 2, 4, 6, 10, 13, 18, 22, 28, 33, 39, 46, 53, 61, 69,
        78, 86, 96, 106, 116, 126, 138, 149, 161, 173, 186, 199, 212, 226, 240, 255};
    /** @brief 64-step gamma table for 256 PWM steps (datasheet Table 18, recommended). */
    static constexpr uint8_t GAMMA_64[64] = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 18, 20, 22,
        24, 26, 29, 32, 35, 38, 41, 44, 47, 50, 53, 57, 61, 65, 69, 73,
        77, 81, 85, 89, 94, 99, 104, 109, 114, 119, 124, 129, 134, 140, 146, 152,
        158, 164, 170, 176, 182, 188, 195, 202, 209, 216, 223, 230, 237, 244, 251, 255};

    explicit Is31fl3731Full(RegisterConnection& connection);

    /** @brief Read the PWM value of one LED from the shadow copy of the frame being drawn (0 if out of range). */
    uint8_t get_pixel(uint8_t row, uint8_t col) const;

    /** @brief Write a whole frame of PWM values.
     *  @param data 144 PWM values, row-major (row * 16 + col).
     *  @param len  Must be 144.
     *  @return false (nothing written) if len != 144.
     */
    bool set_pixels(const uint8_t* data, size_t len);

    /** @brief Set the LED-control (on/off) bit of one LED in the frame being drawn. */
    bool set_led_enabled(uint8_t row, uint8_t col, bool on);

    /** @brief Set the blink-enable bit of one LED in the frame being drawn (needs enable_blink()). */
    bool set_blink(uint8_t row, uint8_t col, bool on);

    /** @brief Set all 18 blink-control bytes of the frame being drawn. */
    void set_blink_all(bool on);

    /** @brief Enable or disable blinking (50 % duty).
     *  @param enabled true to enable.
     *  @param period  Blink period in units of 0.27 s, 0-7.
     *  @return false (no change) if period > 7.
     */
    bool enable_blink(bool enabled, uint8_t period = 1);

    /** @brief Select the frame (0-7) subsequent drawing calls write to; the display is unchanged. @return false if frame > 7. */
    bool select_frame(uint8_t frame);

    /** @brief Show a frame (0-7) in Picture mode. @return false if frame > 7. */
    bool show_frame(uint8_t frame);

    /** @brief Initialise a frame: LED-control 0xFF, PWM and blink 0. @return false if frame > 7. */
    bool clear_frame(uint8_t frame);

    /** @brief Initialise all eight frames. */
    void clear_all_frames();

    /** @brief Copy the PWM values of frame @p src to frame @p dst. @return false if either is > 7. */
    bool copy_frame(uint8_t src, uint8_t dst);

    /** @brief Switch to Picture mode (shows the frame selected by show_frame()). */
    void set_picture_mode();

    /** @brief Start Auto Frame Play.
     *  @param frames      Frames played, 1-7, or 0 for all eight.
     *  @param loops       Loops, 1-7, or 0 for endless.
     *  @param delay_ms    Frame delay in ms (rounded to a multiple of 11 ms, 11-704).
     *  @param start_frame First frame, 0-7.
     *  @return false (nothing written) if an argument is out of range.
     */
    bool start_auto_play(uint8_t frames = 0, uint8_t loops = 0, uint16_t delay_ms = 121, uint8_t start_frame = 0);

    /** @brief Stop Auto Frame Play and keep showing the current frame (reading it clears the movie-finished flag). */
    void stop_auto_play();

    /** @brief Switch Audio Frame Play on, or back to Picture mode. */
    void set_audio_mode(bool enabled);

    /** @brief Let the audio input modulate the matrix intensity. */
    void set_audio_sync(bool enabled);

    /** @brief Configure the audio gain: 0, 3, ... 21 dB, optional AGC (slow or fast). @return false if gain_db is invalid. */
    bool set_audio_gain(uint8_t gain_db, bool agc = false, bool fast = false);

    /** @brief Set the audio ADC sample interval (46 us x a, 0 = 46 us x 256). */
    void set_audio_rate(uint8_t a);

    /** @brief Configure the breath function; exponents 0-7 (fade 26 ms x 2^n, extinguish 3.5 ms x 2^n). @return false if out of range. */
    bool set_breath(bool enabled, uint8_t fade_in = 0, uint8_t fade_out = 0, uint8_t extinguish = 0);

    /** @brief Choose whether frame 1's PWM values apply to all frames. */
    void set_intensity_control(bool use_frame1);

    /** @brief Enter (true) or leave (false) software shutdown; frame registers are kept. */
    void shutdown(bool on = true);

    /** @brief Read FRAME_STATE (bit 4 = movie finished, bits 2:0 = current frame); clears the flag. */
    uint8_t frame_state();

    /** @brief Frame being displayed, 0-7. */
    uint8_t current_frame();

    /** @brief True if an Auto Frame Play movie has finished (clears the flag). */
    bool movie_finished();

    /** @brief Read a function-page register (0-0x0C) and restore the selected page; 0 if reg is out of range. */
    uint8_t read_function_register(uint8_t reg);

    /** @brief Write a function-page register (0-0x0C) and restore the selected page. @return false if reg > 0x0C. */
    bool write_function_register(uint8_t reg, uint8_t value);

    /** @brief Read FRAME_STATE (clears INT) and restore the selected page. */
    uint8_t poll_interrupt();

    /** @brief Subscribe to the end of an Auto Frame Play movie (falling edge of INTB).
     *  @param callback Called with FRAME_STATE.
     *  @param intPin   Optional InputPin overriding connection.intPin().
     */
    void on_interrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe and stop delivery. */
    void off_interrupt();

    /** @brief Poll once and invoke the callback if a movie has finished (call periodically without an INT pin). */
    void service();

private:
    void (*_callback)(uint8_t);
    InputPin* _int_pin_used;
    static Is31fl3731Full* _active_instance;
    static void _edge_trampoline();
    void _write_display();
    bool _set_bit(uint8_t (*cache)[18], uint8_t base, uint8_t row, uint8_t col, bool on);
    void _restore(int16_t prev);
};
