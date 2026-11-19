#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief PCA9685 16-channel, 12-bit PWM LED/servo controller — minimal interface.
 *
 *  Dims LEDs / drives servos on 16 channels with one call each. I2C address
 *  0x40-0x6F or 0x71-0x77 (A0-A5 pins; 0x70 is the All Call address).
 *
 *  Defaults baked in at construction: the chip is put to sleep, PRE_SCALE is
 *  set for 1 kHz (5), then it is woken with auto-increment on and ALLCALL off
 *  and the 1 ms oscillator start is waited for. Outputs are totem-pole, not
 *  inverted, and change on the I2C STOP; all channels keep their power-on
 *  state (off).
 *
 *  @param connection RegisterConnection (I2C) pointing at the device.
 */
class Pca9685Minimal {
public:
    static constexpr uint8_t CHANNELS = 16;

    explicit Pca9685Minimal(RegisterConnection& connection);

    /** @brief Set the duty cycle of one channel (ON = 0, OFF = round(duty * 4096)).
     *
     *  0.0 writes full OFF and 1.0 writes full ON, so no glitch pulses occur.
     *  @param channel 0-15.
     *  @param duty    0.0-1.0.
     *  @return false (and no change) if an argument is out of range.
     */
    bool set_duty(uint8_t channel, float duty);

protected:
    RegisterConnection& _connection;

    void _write(uint8_t reg, const uint8_t* data, size_t len);
    void _write1(uint8_t reg, uint8_t value);
    uint8_t _read1(uint8_t reg);
    static bool _duty_bytes(float duty, uint8_t out[4]);
};

/** @brief PCA9685 full interface — phase-shifted PWM, frequency, servo pulses, modes, addresses.
 *
 *  Extends Pca9685Minimal.
 */
class Pca9685Full : public Pca9685Minimal {
public:
    /** @brief Output state while OE = 1 (MODE2.OUTNE) for set_output_mode(). */
    static constexpr uint8_t OFF_LOW = 0;            ///< LEDn = 0
    static constexpr uint8_t OFF_HIGH_OR_HIGHZ = 1;  ///< LEDn = 1 (totem-pole) or high-Z (open-drain)
    static constexpr uint8_t OFF_HIGHZ = 2;          ///< high-Z

    explicit Pca9685Full(RegisterConnection& connection);

    /** @brief Write the ON and OFF counts of one channel in one transaction (phase-shifted PWM).
     *  @param channel 0-15.
     *  @param on      0-4095.
     *  @param off     0-4095, must differ from @p on.
     *  @return false (and no change) if an argument is out of range.
     */
    bool set_pwm(uint8_t channel, uint16_t on, uint16_t off);

    /** @brief Read the ON and OFF counts of one channel (without the full-ON/OFF flags).
     *  @param channel 0-15.
     *  @param on      Receives the ON count.
     *  @param off     Receives the OFF count.
     *  @return false if @p channel is out of range.
     */
    bool get_pwm(uint8_t channel, uint16_t& on, uint16_t& off);

    /** @brief Force a channel permanently high (full-ON bit set, full-OFF bit cleared).
     *  @param channel 0-15.
     *  @return false if @p channel is out of range.
     */
    bool set_full_on(uint8_t channel);

    /** @brief Force a channel permanently low (full-OFF bit set; it wins over full ON).
     *  @param channel 0-15.
     *  @return false if @p channel is out of range.
     */
    bool set_full_off(uint8_t channel);

    /** @brief Set the same duty cycle on all 16 channels (ALL_LED registers).
     *  @param duty 0.0-1.0.
     *  @return false if @p duty is out of range.
     */
    bool set_all_duty(float duty);

    /** @brief Set the same ON and OFF counts on all 16 channels.
     *  @param on  0-4095.
     *  @param off 0-4095, must differ from @p on.
     *  @return false if an argument is out of range.
     */
    bool set_all_pwm(uint16_t on, uint16_t off);

    /** @brief Set the PWM frequency: PRE_SCALE = round(osc_hz / (4096 * hz)) - 1, clamped 3-255.
     *
     *  Puts the chip to sleep, writes PRE_SCALE, wakes it, waits and restarts
     *  running channels.
     *  @param hz     Wanted frequency in Hz (24-1526 at 25 MHz).
     *  @param osc_hz Oscillator frequency in Hz (25 MHz internal, or the EXTCLK frequency).
     *  @return false if @p hz or @p osc_hz is not positive.
     */
    bool set_frequency(float hz, float osc_hz = 25000000.0f);

    /** @brief Read PRE_SCALE and return the PWM frequency osc_hz / (4096 * (PRE_SCALE + 1)) in Hz.
     *  @param osc_hz Oscillator frequency in Hz.
     */
    float get_frequency(float osc_hz = 25000000.0f);

    /** @brief Set a pulse width in seconds (servo/ESC mode): OFF = round(seconds * frequency * 4096).
     *
     *  Uses the frequency programmed in PRE_SCALE (25 MHz oscillator).
     *  @param channel 0-15.
     *  @param seconds Pulse width, 0 up to one PWM period.
     *  @return false if an argument is out of range.
     */
    bool set_pulse_width(uint8_t channel, float seconds);

    /** @brief Enter low-power mode (MODE1.SLEEP = 1, oscillator off; outputs stop). */
    void sleep();

    /** @brief Leave low-power mode, wait and restart channels that were running. */
    void wake();

    /** @brief Run the datasheet restart sequence (read MODE1, clear SLEEP, wait, write RESTART). */
    void restart();

    /** @brief Configure MODE2: output driver, inversion and the output state while OE = 1.
     *
     *  The update-on-ACK bit is preserved.
     *  @param open_drain True for open-drain, false for totem-pole.
     *  @param invert     True to invert the output logic.
     *  @param off_state  OFF_LOW, OFF_HIGH_OR_HIGHZ or OFF_HIGHZ.
     *  @return false if @p off_state is out of range.
     */
    bool set_output_mode(bool open_drain = false, bool invert = false, uint8_t off_state = OFF_LOW);

    /** @brief Select when outputs change: on ACK (true, MODE2.OCH = 1) or on STOP (false, default). */
    void set_update_on_ack(bool enabled);

    /** @brief Switch to the EXTCLK pin as clock (sticky until power cycle / software reset).
     *
     *  Afterwards pass the external clock frequency as osc_hz to set_frequency() / get_frequency().
     */
    void use_external_clock();

    /** @brief Enable or disable the LED All Call address and set its 7-bit value.
     *  @param enabled True to respond to the All Call address.
     *  @param address 7-bit address (default 0x70).
     *  @return false if @p address is not 7 bit.
     */
    bool set_allcall(bool enabled, uint8_t address = 0x70);

    /** @brief Program and enable/disable one of the three LED Sub Call addresses.
     *  @param index   1-3.
     *  @param address 7-bit address.
     *  @param enabled True to respond to the sub address.
     *  @return false if an argument is out of range.
     */
    bool set_subaddress(uint8_t index, uint8_t address, bool enabled = true);

    /** @brief Return the chip registers to their power-on values by writing the documented defaults.
     *
     *  MODE1 = 0x11, MODE2 = 0x04, PRE_SCALE = 0x1E, outputs off. Does not send the
     *  general-call software reset (see software_reset()).
     */
    void reset();

    /** @brief Send the Software Reset call (0x06) over a connection bound to the general call address 0x00.
     *
     *  Resets every PCA9685 on the bus; wait at least 500 us before using the chips again.
     *  @param general_call Connection bound to I2C address 0x00.
     */
    static void software_reset(Connection& general_call);

    /** @brief Read the raw mode registers.
     *  @param mode1 Receives MODE1.
     *  @param mode2 Receives MODE2.
     */
    void read_mode(uint8_t& mode1, uint8_t& mode2);

private:
    static bool _pwm_bytes(uint16_t on, uint16_t off, uint8_t out[4]);
};
