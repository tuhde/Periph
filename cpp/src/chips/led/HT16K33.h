#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"
#include "../../connection/InputPin.h"

/** @brief HT16K33 16x8 LED matrix driver — minimal interface.
 *
 *  Drives up to 16 ROW x 8 COM = 128 LEDs through a 16-byte display RAM
 *  shadowed in the driver. I2C address 0x70 (A0-A2 select 0x70-0x77).
 *
 *  Defaults baked in at construction: oscillator on, ROW15 pin as a ROW
 *  output, brightness 16/16, blink off, RAM cleared, display on.
 *
 *  @param connection Configured I2C connection pointing at the device.
 */
class HT16K33Minimal {
public:
    static constexpr uint8_t COMMONS = 8;
    static constexpr uint8_t ROWS    = 16;

    static constexpr uint8_t CMD_SYSTEM  = 0x20;
    static constexpr uint8_t CMD_DISPLAY = 0x80;
    static constexpr uint8_t CMD_ROW_INT = 0xA0;
    static constexpr uint8_t CMD_DIMMING = 0xE0;
    static constexpr uint8_t CMD_KEY_PTR = 0x40;
    static constexpr uint8_t CMD_INT_PTR = 0x60;

    /** @brief Construct the driver and run the initialization sequence. */
    explicit HT16K33Minimal(Connection& connection);

    /** @brief Zero the shadow RAM and write it to the chip. */
    void clear();

    /** @brief Set or clear one LED in the shadow RAM (does not write to the chip).
     *  @param com COM line, 0-7.
     *  @param row ROW line, 0-15.
     *  @param on  true to light the LED.
     *  @return false (and no change) if com or row is out of range.
     */
    bool set_pixel(uint8_t com, uint8_t row, bool on = true);

    /** @brief Write raw RAM bytes at @p address and update the shadow RAM.
     *  @param data    1-16 bytes of display data.
     *  @param len     Number of bytes.
     *  @param address Start RAM address, 0-15.
     *  @return false (nothing written) if len is 0 or address + len exceeds 16.
     */
    bool write_raw(const uint8_t* data, size_t len, uint8_t address = 0);

    /** @brief Write the whole 16-byte shadow RAM to the chip. */
    void show();

    /** @brief Set the dimming level, 0-15, giving (level + 1)/16 duty. Out-of-range values are ignored. */
    void set_brightness(uint8_t level);

protected:
    Connection& _connection;
    uint8_t _ram[16];
    uint8_t _brightness;
    uint8_t _blink;
    bool    _display;
    uint8_t _row_int;

    void _command(uint8_t byte);
    void _apply_display();
};

/** @brief HT16K33 full interface — display control, standby, key scan, INT output.
 *
 *  Key-press delivery uses the InputPin passed to on_interrupt() (or
 *  connection.intPin()); without one, call service() periodically.
 *
 *  @param connection Configured I2C connection pointing at the device.
 */
class HT16K33Full : public HT16K33Minimal {
public:
    explicit HT16K33Full(Connection& connection);

    /** @brief Turn the display output on (keeps the blink setting). */
    void display_on();

    /** @brief Turn the display output off (keeps the blink setting). */
    void display_off();

    /** @brief Set the blink frequency: 0 (off), 2, 1 or 0.5 Hz.
     *  @return false (no change) for any other value.
     */
    bool set_blink(float frequency);

    /** @brief Stop the oscillator (display off, key scan stopped). */
    void standby();

    /** @brief Start the oscillator and restore display, dimming, ROW/INT and RAM. */
    void wake();

    /** @brief Select the function of the ROW15/INT pin.
     *  @param enable      true makes the pin an interrupt output (ROW15/K13 unusable).
     *  @param active_high INT polarity when enabled (default active low).
     */
    void set_int_output(bool enable, bool active_high = false);

    /** @brief Read and clear the key data RAM.
     *  @return Key bitmask, bit k*13 + (K-1) for key input K (1-13) on key source KSk (0-2).
     */
    uint64_t read_keys();

    /** @brief Read the INT flag; true if a key press is pending. */
    bool key_pressed();

    /** @brief Read one LED state from the shadow RAM. */
    bool get_pixel(uint8_t com, uint8_t row) const;

    /** @brief Subscribe to key-press events.
     *  @param callback Called with the key bitmask.
     *  @param intPin   Optional InputPin overriding connection.intPin().
     */
    void on_interrupt(void (*callback)(uint64_t keys), InputPin* intPin = nullptr);

    /** @brief Unsubscribe and stop delivery. */
    void off_interrupt();

    /** @brief Check the INT flag and, if set, read (and clear) the key data; 0 if none. */
    uint64_t poll_interrupt();

    /** @brief Poll once and invoke the callback if keys are pending (call periodically without an INT pin). */
    void service();

private:
    void (*_callback)(uint64_t);
    InputPin* _int_pin_used;
    static HT16K33Full* _active_instance;
    static void _edge_trampoline();
};
