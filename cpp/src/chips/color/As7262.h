#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/InputPin.h"

/** @brief Six calibrated spectral channel values (floats as delivered by the AS7262). */
struct AS7262Channels {
    float violet;  ///< 450 nm
    float blue;    ///< 500 nm
    float green;   ///< 550 nm
    float yellow;  ///< 570 nm
    float orange;  ///< 600 nm
    float red;     ///< 650 nm
};

/** @brief Six raw 16-bit spectral channel counts. */
struct AS7262RawChannels {
    uint16_t violet;
    uint16_t blue;
    uint16_t green;
    uint16_t yellow;
    uint16_t orange;
    uint16_t red;
};

/** @brief AS7262 6-channel visible spectral sensor (ams OSRAM) — minimal interface.
 *
 * Measures violet (450 nm), blue (500 nm), green (550 nm), yellow (570 nm), orange (600 nm) and
 * red (650 nm). Every sensor register is a *virtual register* reached through three hardware
 * registers (STATUS 0x00, WRITE 0x01, READ 0x02); this class implements the handshake.
 *
 * begin() soft-resets the chip and applies the Minimal defaults: gain 16x, integration time
 * 0x3B (165.2 ms), one-shot bank mode 3, both LED outputs off, INT pin disabled. read() runs one
 * one-shot conversion.
 *
 * @param connection Register-capable I²C connection pointing at the device (address 0x49). The
 *        constructor does no bus access; call begin() once.
 */
class AS7262Minimal {
public:
    AS7262Minimal(RegisterConnection& connection);

    /** @brief Wait for the chip to boot (HW_VERSION = 0x40), soft-reset it and write the defaults.
     *  @return true on success; false on a bus error, time-out or a missing device.
     */
    bool begin();

    /** @brief Run one one-shot conversion and return the six calibrated channels.
     *  @param[out] out Calibrated values.
     *  @return true on success; false on a bus error or a conversion time-out.
     */
    bool read(AS7262Channels& out);

    /** @brief Read the device temperature in °C; NAN on failure. */
    float temperature();

    /** @brief Read one virtual register through the STATUS/WRITE/READ handshake.
     *  @param reg Virtual register address (0x00-0x2B).
     *  @param[out] value The register byte.
     *  @return true on success; false on a bus error or a handshake time-out (about 1 s).
     */
    bool read_virtual(uint8_t reg, uint8_t& value);

    /** @brief Write one virtual register through the STATUS/WRITE handshake.
     *  @param reg Virtual register address (0x04, 0x05 or 0x07 are writable).
     *  @param value Byte to write.
     *  @return true on success.
     */
    bool write_virtual(uint8_t reg, uint8_t value);

protected:
    RegisterConnection& _connection;

    bool _hw_read(uint8_t reg, uint8_t& value);
    bool _wait_status(uint8_t mask, uint8_t want);
    bool _wait_boot();
    bool _reset();
    bool _start_one_shot();
    bool _wait_data();
    bool _read_calibrated(AS7262Channels& out);
    bool _read_float(uint8_t first_reg, float& value);
    bool _update_control(uint8_t mask, uint8_t bits);
};

/** @brief AS7262 full interface — raw counts, gain, integration time, bank modes, LEDs, interrupt, versions.
 *
 * Extends AS7262Minimal.
 *
 * @param connection Register-capable I²C connection pointing at the device (address 0x49).
 */
class AS7262Full : public AS7262Minimal {
public:
    AS7262Full(RegisterConnection& connection);

    /** @brief Run one one-shot conversion and return the raw 16-bit counts. */
    bool read_raw(AS7262RawChannels& out);

    /** @brief Start a one-shot conversion without waiting for it (BANK = 3). */
    bool measure();

    /** @brief True if a conversion result is waiting (CONTROL_SETUP.DATA_RDY); false on error too. */
    bool data_ready();

    /** @brief Read the last calibrated results without starting a conversion. */
    bool read_calibrated(AS7262Channels& out);

    /** @brief Read the last raw counts without starting a conversion. */
    bool read_raw_last(AS7262RawChannels& out);

    /** @brief Read one calibrated channel (0-5 = V, B, G, Y, O, R) of the last conversion. NAN on failure or a bad index. */
    float read_channel(uint8_t index);

    /** @brief Read one raw channel (0-5) of the last conversion.
     *  @return true on success; false for a bad index or a bus error.
     */
    bool read_channel_raw(uint8_t index, uint16_t& value);

    /** @brief Set the analog gain.
     *  @param gain 0-3 (index), or 1, 3.7, 16 or 64 (1 means 1x).
     *  @return false for an unsupported gain (before any bus access) or a bus error.
     */
    bool set_gain(float gain);

    /** @brief The analog gain multiplier (1, 3.7, 16 or 64); 0 on failure. */
    float get_gain();

    /** @brief Set the integration time per bank.
     *  @param ms 2.8-714 ms, rounded to a multiple of 2.8 ms; false outside that range.
     */
    bool set_integration_time(float ms);

    /** @brief The integration time per bank in ms; 0 on failure. */
    float get_integration_time();

    /** @brief Set the bank mode: 0 (V,B,G,Y), 1 (G,Y,O,R), 2 (all six) continuous, 3 one-shot. */
    bool set_mode(uint8_t mode);

    /** @brief The bank mode (0-3); 0xFF on failure. */
    uint8_t get_mode();

    /** @brief Switch to continuous conversions (bank mode 0, 1 or 2). */
    bool set_continuous(uint8_t mode = 2);

    /** @brief Enable or disable the INT pin output (CONTROL_SETUP.INT). */
    bool set_interrupt(bool enabled);

    /** @brief Control the driver LED output; @p current_ma is rounded to 12.5, 25, 50 or 100 mA (false above 100). */
    bool set_led(bool on, float current_ma = 12.5f);

    /** @brief Control the indicator LED output; @p current_ma is rounded to 1, 2, 4 or 8 mA (false above 8). */
    bool set_indicator_led(bool on, float current_ma = 1.0f);

    /** @brief Soft-reset the chip (all registers return to their defaults). */
    bool soft_reset();

    /** @brief Read the hardware version registers; expect type 0x40 and version 0x3E. */
    bool hardware_version(uint8_t& type, uint8_t& version);

    /** @brief Read the firmware version. */
    bool firmware_version(uint8_t& major, uint8_t& minor, uint8_t& sub);

    /** @brief The device type byte (virtual register 0x00, expect 0x40); 0 on failure. */
    uint8_t device_type();

    /** @brief Read CONTROL_SETUP (releases INT) and return DATA_RDY (0 or 1). */
    uint8_t poll_interrupt();

    /** @brief Enable the INT output and subscribe to the INT pin (falling edge).
     *  @param callback Called with 1 on each INT edge.
     *  @param intPin Optional InputPin overriding connection.intPin(); without a pin nothing is registered.
     */
    void on_interrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);

    /** @brief Unsubscribe from the INT pin and disable the INT output. */
    void off_interrupt();

private:
    bool _read_raw_regs(AS7262RawChannels& out);
    static void _edge_trampoline();

    void (*_callback)(uint8_t) = nullptr;
    InputPin* _int_pin = nullptr;
    static AS7262Full* _active_instance;
};
