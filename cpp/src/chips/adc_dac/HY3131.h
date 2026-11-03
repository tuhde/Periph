#pragma once
#include <stdint.h>
#include "../../connection/Connection.h"

/** @brief HY3131 50,000-count DMM analog front end — base class with shared register access.
 *
 * The SPI framing is non-standard (7-bit address + R/W bit, one blank clock on
 * reads, auto-increment), so the driver uses the bare Connection's write() and
 * write_read(). INTF is polled over SPI for data-ready; no IRQ pin is needed.
 */
class _HY3131Base {
public:
    /** @brief INTF flag bits. */
    static constexpr uint8_t FLAG_CTF  = 0x01;
    static constexpr uint8_t FLAG_AD2F = 0x02;
    static constexpr uint8_t FLAG_AD1F = 0x04;
    static constexpr uint8_t FLAG_LPFF = 0x08;
    static constexpr uint8_t FLAG_RMSF = 0x10;
    static constexpr uint8_t FLAG_BORF = 0x80;

    /** @brief Read @p n consecutive registers starting at @p reg (auto-increment, n <= 8).
     *  @param reg Start address 0x00–0x37.
     *  @param buf Output buffer.
     *  @param n   Number of bytes.
     */
    void read_register(uint8_t reg, uint8_t* buf, uint8_t n);

    /** @brief Write @p n bytes starting at @p reg (auto-increment, n <= 8). */
    void write_register(uint8_t reg, const uint8_t* data, uint8_t n);

    /** @brief Write a single byte to @p reg. */
    void write_register(uint8_t reg, uint8_t value) { write_register(reg, &value, 1); }

protected:
    _HY3131Base(Connection& connection, float vref) : _connection(connection), _vref(vref) {}

    /** @brief Reset the chip and apply the Minimal defaults. */
    void _init();
    /** @brief Read one register. */
    uint8_t _read1(uint8_t reg);
    /** @brief Poll INTF until @p mask is set (latching all flags seen); false on 2 s timeout. */
    bool _wait_flag(uint8_t mask);
    /** @brief Read a 3-byte little-endian register triple as unsigned. */
    int64_t _read24(uint8_t reg);
    /** @brief Sign-extend @p value from @p bits. */
    static int64_t _sext(int64_t value, int bits);

    Connection& _connection;
    float       _vref;
    uint8_t     _flags = 0;
    uint8_t     _ig = 0;
    uint8_t     _rg = 0;
    float       _dcos = 0.0f;

    static constexpr uint8_t _REG_AD1 = 0x00, _REG_AD2 = 0x03, _REG_LPF = 0x06, _REG_RMS = 0x09;
    static constexpr uint8_t _REG_PKHMIN = 0x0E, _REG_PKHMAX = 0x11, _REG_CTSTA = 0x14;
    static constexpr uint8_t _REG_CTC = 0x15, _REG_CTB = 0x18, _REG_CTA = 0x1B;
    static constexpr uint8_t _REG_INTF = 0x1E, _REG_INTE = 0x1F;
    static constexpr uint8_t _REG_R20 = 0x20, _REG_R22 = 0x22, _REG_R23 = 0x23, _REG_R24 = 0x24;
    static constexpr uint8_t _REG_R25 = 0x25, _REG_R26 = 0x26, _REG_R27 = 0x27, _REG_R28 = 0x28;
    static constexpr uint8_t _REG_R29 = 0x29, _REG_R2A = 0x2A, _REG_R2F = 0x2F, _REG_R30 = 0x30;
    static constexpr uint8_t _REG_R31 = 0x31, _REG_R33 = 0x33, _REG_R34 = 0x34, _REG_R35 = 0x35;
    static constexpr uint8_t _REG_R37 = 0x37;
};

/** @brief HY3131 minimal driver — AD1 differential voltage with sensible defaults.
 *
 * Defaults: crystal oscillator on, bandgap REFO (1.2 V), AD1 input PB2 (+) vs AGND (−),
 * reference REFO/AGND, input gain 0.9, reference gain 1.0, buffers off, no chop,
 * OSR 32768 (5 Hz).
 */
class HY3131Minimal : public _HY3131Base {
public:
    /** @brief Sentinel returned by read_raw() on timeout (outside the 24-bit range). */
    static constexpr int32_t TIMEOUT = INT32_MIN;

    /** @brief Construct and initialise the HY3131.
     *  @param connection SPI connection (Mode 0, MSB first, CS active low).
     *  @param vref       Voltage on REFO in V (1.2 V from the internal bandgap).
     */
    HY3131Minimal(Connection& connection, float vref = 1.2f) : _HY3131Base(connection, vref) { _init(); }

    /** @brief Report whether a new AD1 conversion is pending (latches INTF). */
    bool data_ready();

    /** @brief Wait for a conversion and return the signed 24-bit AD1 code.
     *  @return Code −8388608 … 8388607, or TIMEOUT if no conversion completes within 2 s.
     */
    int32_t read_raw();

    /** @brief Read AD1 as a differential input voltage.
     *  @return Voltage in V (NAN on timeout).
     */
    float read_voltage();
};

/** @brief HY3131 full driver — AD1/AD2/AD3, temperature, LPF, RMS, peak hold, counter, interrupts. */
class HY3131Full : public HY3131Minimal {
public:
    HY3131Full(Connection& connection, float vref = 1.2f) : HY3131Minimal(connection, vref) {}

    /** @brief Software-reset the chip and re-apply the Minimal defaults. */
    void reset();

    /** @brief Configure AD1.
     *  @param osr        0–7 → OSR1 256 … 32768.
     *  @param chop       0–3 (1x enables the chopper and halves the rate).
     *  @param input_gain 0–3 → 0.9, 1.8, 2.7, 3.6.
     *  @param ref_gain   0 → 1.0, 1 → 0.333.
     *  @param offset     0–7 DCOS code.
     *  @param buffers    4-bit mask: bit3 RH, bit2 RL, bit1 IP, bit0 IN.
     */
    void configure_ad1(uint8_t osr, uint8_t chop, uint8_t input_gain, uint8_t ref_gain,
                       uint8_t offset, uint8_t buffers);

    /** @brief Select AD1 inputs: @p pos 0–15 (SAD1FP), @p neg 0–7 (SAD1FN), @p filter_r 0–3 (SFT1). */
    void set_ad1_input(uint8_t pos, uint8_t neg, uint8_t filter_r);

    /** @brief Select the AD1 reference: @p high 0–6 (SAD1RH), @p low 0–6 (SAD1RL). */
    void set_ad1_reference(uint8_t high, uint8_t low);

    /** @brief Read TCode = (AD1[SAD1I=11] − AD1[SAD1I=10]) / 2 from the on-chip sensor. */
    int32_t read_temperature_code();

    /** @brief Read the die temperature using a one-point calibration.
     *  @param tcode_25 read_temperature_code() measured at 25 °C.
     *  @return Temperature in °C.
     */
    float read_temperature(int32_t tcode_25);

    /** @brief Enable AD2 (and AD3 when @p use_ad3).
     *  @param osr        0–7 (OSR 32 … 1024).
     *  @param input_gain 0–3 → 0.5, 1.0, 1.5, 2.0.
     *  @param ref_gain   0 → 1.0, 1 → 0.333.
     *  @param chop       Enable the input chopper.
     *  @param clk_div4   Modulator clock SYSCLK/4 instead of /2.
     *  @param source     SAD2IP 0–3 (OP1O, OP2O, PB4, PB7).
     *  @param use_ad3    Show AD3 instead of AD2 in the data registers (SDO23=1).
     */
    void enable_ad2(uint8_t osr, uint8_t input_gain, uint8_t ref_gain, bool chop, bool clk_div4,
                    uint8_t source, bool use_ad3);

    /** @brief Wait for AD2 (or AD3) and return the signed 19-bit code (TIMEOUT on timeout). */
    int32_t read_ad2_raw();

    /** @brief Enable the low-pass filter; @p bw 0–7 → OSR4 128 … 16384. */
    void enable_lpf(uint8_t bw);

    /** @brief Wait for the LPF and return the signed 19-bit code (TIMEOUT on timeout). */
    int32_t read_lpf_raw();

    /** @brief Enable the RMS converter (requires AD2 and the LPF). */
    void enable_rms();

    /** @brief Wait for RMS and return sqrt(RMS accumulator) in AD2 LSB units (NAN on timeout). */
    float read_rms();

    /** @brief Enable peak hold; @p source 0–3 → AD2, AD1<23:5>, LPF, LPF. Re-enabling resets min/max. */
    void enable_peak_hold(uint8_t source);

    /** @brief Read the peak-hold registers into @p min_code / @p max_code (signed 19-bit). */
    void read_peak(int32_t& min_code, int32_t& max_code);

    /** @brief Start the frequency counter; @p gate_preload (0–0xFFFF) goes to CTA<23:8>. */
    void start_counter(uint16_t gate_preload);

    /** @brief Wait for the counter and read it.
     *  @return false on timeout; otherwise true with the outputs filled.
     */
    bool read_counter(uint32_t& cta, uint32_t& ctb, uint32_t& ctc, bool& overflow);

    /** @brief Read INTF (clears it on the chip); latched flags are included and cleared. */
    uint8_t read_flags();

    /** @brief Write INTE (RMSIE 4, LPFIE 3, AD1IE 2, AD2IE 1, CTIE 0). */
    void enable_interrupts(uint8_t mask);
};
