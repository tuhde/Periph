#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief MCP4728 quad-channel 12-bit voltage-output DAC — minimal interface.
 *
 * Provides simple voltage output as a fraction of V_DD for any of the four
 * channels (A–D) plus a convenience method to update all four channels
 * simultaneously. No configuration required beyond the transport. V_REF is
 * fixed at external (V_DD), gain is fixed at ×1, and power-down is off.
 * EEPROM is never written by this class.
 *
 * @param transport Configured I²C transport pointing at the device (0x60–0x67).
 */
class MCP4728Minimal {
public:
    explicit MCP4728Minimal(Transport& transport);

    /** @brief Set one channel's DAC output as a fraction of V_DD.
     *  @param channel  Channel index 0 (A) – 3 (D).
     *  @param fraction Output voltage as a fraction of V_DD (0.0–1.0).
     */
    void set_voltage(uint8_t channel, float fraction);

    /** @brief Set one channel's raw 12-bit DAC code.
     *  @param channel Channel index 0 (A) – 3 (D).
     *  @param code    Raw 12-bit DAC code (0–4095).
     */
    void set_raw(uint8_t channel, uint16_t code);

    /** @brief Update all four channels simultaneously using Fast Write.
     *  @param fractions Pointer to 4 floats (one per channel A–D), each 0.0–1.0.
     */
    void set_all(const float* fractions);

protected:
    static constexpr uint8_t CMD_MULTI_WRITE_BASE  = 0x40; // [0 1 0 0 0 DAC1 DAC0 UDAC]

    Transport& _transport;

    /** @brief Multi-Write: writes one channel's volatile DAC register.
     *  @param channel  Channel index 0–3.
     *  @param code     Raw 12-bit DAC code.
     *  @param vref     0 = external (V_DD), 1 = internal (2.048 V).
     *  @param pd       Power-down mode 0–3.
     *  @param gain     0 = ×1, 1 = ×2.
     *  @param udac     0 = update V_OUT immediately, 1 = hold.
     */
    void _multi_write(uint8_t channel, uint16_t code, uint8_t vref,
                      uint8_t pd, uint8_t gain, uint8_t udac);
};

/** @brief MCP4728 full interface — extends MCP4728Minimal with EEPROM, V_REF, gain, power-down, and read-back.
 *
 * Adds per-channel V_REF and gain configuration, all-channel V_REF/gain/
 * power-down commands, write-with-EEPROM persistence (Single and Sequential
 * Write), General Call reset/wake-up/software-update, and full 24-byte
 * read-back of all channel DAC input registers and EEPROM contents.
 *
 * @param transport Configured I²C transport pointing at the device (0x60–0x67).
 */
class MCP4728Full : public MCP4728Minimal {
public:
    static constexpr uint8_t PD_NORMAL   = 0;
    static constexpr uint8_t PD_1K_GND   = 1;
    static constexpr uint8_t PD_100K_GND = 2;
    static constexpr uint8_t PD_500K_GND = 3;

    static constexpr uint8_t VREF_EXTERNAL = 0;
    static constexpr uint8_t VREF_INTERNAL = 1;

    static constexpr uint8_t GAIN_X1 = 0;
    static constexpr uint8_t GAIN_X2 = 1;

    explicit MCP4728Full(Transport& transport);

    /** @brief Set one channel's output and persist to EEPROM (Single Write). */
    void set_voltage_eeprom(uint8_t channel, float fraction, uint8_t vref, uint8_t gain);

    /** @brief Set one channel's raw 12-bit code and persist to EEPROM. */
    void set_raw_eeprom(uint8_t channel, uint16_t code, uint8_t vref, uint8_t gain);

    /** @brief Update all four channels and EEPROM (Sequential Write from A to D). */
    void set_all_eeprom(const float* fractions, const uint8_t* vrefs, const uint8_t* gains);

    /** @brief Set V_REF for all four channels (volatile register only). */
    void set_vref(uint8_t vref_a, uint8_t vref_b, uint8_t vref_c, uint8_t vref_d);

    /** @brief Set gain for all four channels (volatile register only). */
    void set_gain(uint8_t gain_a, uint8_t gain_b, uint8_t gain_c, uint8_t gain_d);

    /** @brief Set power-down mode for all four channels (volatile register only). */
    void set_power_down(uint8_t pd_a, uint8_t pd_b, uint8_t pd_c, uint8_t pd_d);

    /** @brief Per-channel state of one input-register + EEPROM pair. */
    struct ChannelState {
        uint16_t code;
        uint8_t  vref;
        uint8_t  gain;
        uint8_t  power_down;
        uint16_t eeprom_code;
        uint8_t  eeprom_vref;
        uint8_t  eeprom_gain;
        uint8_t  eeprom_power_down;
    };

    /** @brief Read all four channels' DAC input registers and EEPROM. */
    struct ReadResult {
        ChannelState channel[4];
        bool         eeprom_ready;
    };
    ReadResult read();

    /** @brief Check if the EEPROM write is complete (RDY/BSY = 1). */
    bool is_eeprom_ready();

    /** @brief Send General Call Software Update (0x00, 0x08) to latch all V_OUT. */
    void software_update();

    /** @brief Send General Call Wake-Up (0x00, 0x09) to clear all PD bits. */
    void wake_up();

    /** @brief Send General Call Reset (0x00, 0x06) to reload EEPROM into all DAC registers. */
    void reset();

private:
    static constexpr uint8_t CMD_SINGLE_WRITE      = 0x58; // [0 1 0 1 1 DAC1 DAC0 UDAC]
    static constexpr uint8_t CMD_SEQUENTIAL_BASE   = 0x50; // [0 1 0 1 0 DAC1 DAC0 UDAC]
    static constexpr uint8_t CMD_WRITE_VREF        = 0x80; // [1 0 0 X Vref_A Vref_B Vref_C Vref_D]
    static constexpr uint8_t CMD_WRITE_GAIN        = 0xC0; // [1 1 0 X Gx_A Gx_B Gx_C Gx_D]
    static constexpr uint8_t CMD_WRITE_POWERDOWN   = 0xA0; // [1 0 1 X PD1_A PD0_A PD1_B PD0_B]
    static constexpr uint8_t ADDR_GENERAL_CALL     = 0x00;

    static constexpr uint8_t GC_RESET        = 0x06;
    static constexpr uint8_t GC_SOFTWARE_UPD = 0x08;
    static constexpr uint8_t GC_WAKE         = 0x09;

    void _single_write(uint8_t channel, uint16_t code, uint8_t vref,
                       uint8_t pd, uint8_t gain, uint8_t udac);
};
