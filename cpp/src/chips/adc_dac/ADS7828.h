#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief ADS7828 12-bit, 8-channel sampling ADC (TI / Burr-Brown) — minimal interface.
 *
 *  The chip has no registers: one command byte selects the input mode
 *  (single-ended or differential), the channel and the power-down state and
 *  starts a conversion; the 12-bit result is read back as two bytes.
 *  `connection.read(command, buf, 2)` issues exactly the datasheet's
 *  write-command / repeated-START / read-two-bytes sequence. The I²C address
 *  is 0x48–0x4B (A1/A0 tied to GND or VDD).
 *
 *  Reads the eight single-ended inputs (CHn against COM) as raw codes or
 *  voltages. Defaults to the internal 2.5 V reference (LSB 610 µV), kept on
 *  between conversions. The constructor makes no bus access (so the object
 *  may be global on Arduino); begin() — or the first conversion — sends the
 *  reference-on command and waits 1 ms when the internal reference is selected.
 *
 *  @param connection RegisterConnection (I²C) pointing at the device (0x48–0x4B).
 *  @param vref       0 (default) = internal 2.5 V reference (REFIN/REFOUT decoupled
 *                    with 0.1 µF, nothing else driving it); a positive value = external
 *                    reference voltage in volts (0.05 V … VDD) with the internal reference off.
 */
class ADS7828Minimal {
public:
    /** @brief 7-bit I²C address with A1 = A0 = GND. */
    static constexpr uint8_t I2C_ADDRESS = 0x48;

    explicit ADS7828Minimal(RegisterConnection& connection, float vref = 0.0f);

    /** @brief Enable the internal reference now (no-op with an external one).
     *  Sends the lone command 0x08 and waits 1 ms for the reference to settle.
     *  Optional: the first conversion does this automatically. */
    void begin();

    /** @brief Read a single-ended channel (CHn against COM) as a 12-bit code.
     *  @param channel Channel number 0–7 (clamped).
     *  @return Raw 12-bit code 0–4095. */
    uint16_t readRaw(int channel);

    /** @brief Read a single-ended channel as a voltage.
     *  @param channel Channel number 0–7 (clamped).
     *  @return Input voltage in volts, `raw / 4096 × vref`. */
    float readVoltage(int channel);

    /** @brief Read all eight single-ended channels, one conversion each.
     *  @param out Array of at least 8 floats; receives the voltages of CH0…CH7 in volts. */
    void readAll(float* out);

protected:
    static constexpr uint8_t PD_ALL_DOWN       = 0;
    static constexpr uint8_t PD_REF_OFF_ADC_ON = 1;
    static constexpr uint8_t PD_REF_ON_ADC_OFF = 2;
    static constexpr uint8_t PD_REF_ON_ADC_ON  = 3;
    static constexpr uint8_t CMD_REF_ON        = 0x08;  // SD=0, C=0, PD=10
    static constexpr uint8_t CMD_POWER_DOWN    = 0x00;  // SD=0, C=0, PD=00

    RegisterConnection& _connection;
    bool _internal;
    bool _refOn;
    float _vref;
    uint8_t _pd;

    void _selectReference(float vref);
    void _enableReference();
    uint16_t _convert(bool singleEnded, uint8_t code);
};

/** @brief ADS7828 full interface — extends Minimal with differential reads
 *  (both polarities), a signed differential read, run-time reference
 *  switching, power-down control and multi-channel scans.
 *
 *  @param connection RegisterConnection (I²C) pointing at the device (0x48–0x4B).
 *  @param vref       0 = internal 2.5 V reference, or an external reference voltage in volts.
 */
class ADS7828Full : public ADS7828Minimal {
public:
    /** @brief PD=00: power down between conversions (internal reference off). */
    static constexpr uint8_t POWER_DOWN_BETWEEN = 0;
    /** @brief PD=01: internal reference off, ADC on. */
    static constexpr uint8_t REF_OFF_ADC_ON     = 1;
    /** @brief PD=10: internal reference on, ADC off. */
    static constexpr uint8_t REF_ON_ADC_OFF     = 2;
    /** @brief PD=11: internal reference on, ADC on. */
    static constexpr uint8_t REF_ON_ADC_ON      = 3;

    explicit ADS7828Full(RegisterConnection& connection, float vref = 0.0f);

    /** @brief Read a differential pair as a 12-bit code.
     *  @param pair     Pair index 0–3 (CH0/CH1 … CH6/CH7), clamped.
     *  @param inverted If true the positive input is CH(2·pair+1).
     *  @return Raw 12-bit code 0–4095; a negative differential voltage reads 0. */
    uint16_t readDifferentialRaw(int pair, bool inverted = false);

    /** @brief Read a differential pair as a voltage.
     *  @param pair     Pair index 0–3, clamped.
     *  @param inverted If true the positive input is CH(2·pair+1).
     *  @return Differential voltage in volts (≥ 0). */
    float readDifferential(int pair, bool inverted = false);

    /** @brief Read V(CH2p) − V(CH2p+1) with sign, using two conversions.
     *  @param pair Pair index 0–3, clamped.
     *  @return Signed differential voltage in volts (−vref … +vref). */
    float readSignedDifferential(int pair);

    /** @brief Switch between the internal and an external reference.
     *  @param vref 0 = internal 2.5 V (re-runs the turn-on and 1 ms settle wait); a positive value = external reference in volts (PD1 = 0 from then on). */
    void setReference(float vref);

    /** @brief Reference voltage used for scaling.
     *  @return Volts (2.5 for the internal reference). */
    float getReference() const;

    /** @brief Whether the internal reference is selected.
     *  @return true for the internal reference. */
    bool usesInternalReference() const;

    /** @brief Set the PD bits used in all following conversion commands.
     *  @param pd 0 = POWER_DOWN_BETWEEN, 1 = REF_OFF_ADC_ON, 2 = REF_ON_ADC_OFF, 3 = REF_ON_ADC_ON.
     *  @return false (and nothing changed) if pd is outside 0–3, or if the internal reference is selected and pd has PD1 = 0. */
    bool setPowerMode(uint8_t pd);

    /** @brief Power the chip down completely (≈ 400 nA) with a lone command (PD = 00).
     *  With the internal reference this turns it off; the next read re-runs the turn-on and settle wait. */
    void powerDown();

    /** @brief Read several single-ended channels in order as raw codes.
     *  @param channels Channel numbers 0–7.
     *  @param count    Number of entries in @p channels and @p out.
     *  @param out      Receives one raw 12-bit code per channel. */
    void readScan(const uint8_t* channels, size_t count, uint16_t* out);

    /** @brief Size of one code step.
     *  @return `vref / 4096` in volts (610 µV at 2.5 V). */
    float lsb() const;
};
