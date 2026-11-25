#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief ES8311 low-power mono audio codec (I²C control port) — minimal interface.
 *
 * Brings the codec up for voice playback and recording and lets the application
 * set output volume, mute and microphone gain. The audio stream itself runs over
 * a separate I²S port that this library does not provide.
 *
 * Construction runs the initialization sequence: software reset, clock tree for
 * the given MCLK / sample rate, I²S 16-bit slave, analog path, analog microphone
 * at 24 dB, ADC 0 dB, DAC volume −95.5 dB (silent until set_volume()).
 * A MCLK : sample-rate combination without a valid divider set aborts, matching
 * the repo's convention for failed construction. The registers 0x04, 0x0B–0x0C
 * and 0x10–0x13 are undocumented in the user guide; the vendor reference-driver
 * values are used — verify on hardware.
 *
 * @param connection     RegisterConnection (I²C) pointing at the device (0x18 / 0x19).
 * @param mclk_hz        Master clock in Hz (default 12.288 MHz).
 * @param sample_rate_hz Audio sample rate in Hz (default 48 kHz).
 * @param dvdd_volts     DVDD supply, selects the 3.3 V or 1.8 V clock limits.
 */
class ES8311Minimal {
public:
    explicit ES8311Minimal(RegisterConnection& connection, uint32_t mclk_hz = 12288000,
                           uint32_t sample_rate_hz = 48000, float dvdd_volts = 3.3f);

    /** @brief Set the DAC digital volume.
     *  @param db −95.5 … +32 dB, rounded to the nearest 0.5 dB step (clamped).
     */
    void set_volume(float db);

    /** @brief Mute or unmute the DAC (DSM and DEM mute together, 0x31 bits 6 and 5).
     *  @param muted true to mute.
     */
    void set_mute(bool muted);

    /** @brief Set the analog microphone PGA gain.
     *  @param db 0 … 30 dB, rounded to the nearest 3 dB step (clamped).
     */
    void set_mic_gain(float db);

protected:
    static constexpr uint8_t REG_RESET = 0x00;
    static constexpr uint8_t REG_CLK1 = 0x01;
    static constexpr uint8_t REG_CLK2 = 0x02;
    static constexpr uint8_t REG_CLK3 = 0x03;
    static constexpr uint8_t REG_CLK4 = 0x04;
    static constexpr uint8_t REG_CLK5 = 0x05;
    static constexpr uint8_t REG_CLK6 = 0x06;
    static constexpr uint8_t REG_CLK7 = 0x07;
    static constexpr uint8_t REG_CLK8 = 0x08;
    static constexpr uint8_t REG_SDP_IN = 0x09;
    static constexpr uint8_t REG_SDP_OUT = 0x0A;
    static constexpr uint8_t REG_SYS_0B = 0x0B;
    static constexpr uint8_t REG_SYS_0C = 0x0C;
    static constexpr uint8_t REG_SYS_PWR = 0x0D;
    static constexpr uint8_t REG_SYS_ADC = 0x0E;
    static constexpr uint8_t REG_SYS_LP = 0x0F;
    static constexpr uint8_t REG_SYS_10 = 0x10;
    static constexpr uint8_t REG_SYS_11 = 0x11;
    static constexpr uint8_t REG_SYS_DAC = 0x12;
    static constexpr uint8_t REG_SYS_HP = 0x13;
    static constexpr uint8_t REG_SYS_MIC = 0x14;
    static constexpr uint8_t REG_ADC_RAMP = 0x15;
    static constexpr uint8_t REG_ADC_SCALE = 0x16;
    static constexpr uint8_t REG_ADC_VOLUME = 0x17;
    static constexpr uint8_t REG_ADC_ALC1 = 0x18;
    static constexpr uint8_t REG_ADC_ALC2 = 0x19;
    static constexpr uint8_t REG_ADC_AUTOMUTE = 0x1A;
    static constexpr uint8_t REG_ADC_HPF1 = 0x1B;
    static constexpr uint8_t REG_ADC_HPF2_EQ = 0x1C;
    static constexpr uint8_t REG_ADCEQ = 0x1D;
    static constexpr uint8_t REG_DAC_MUTE = 0x31;
    static constexpr uint8_t REG_DAC_VOLUME = 0x32;
    static constexpr uint8_t REG_DAC_DRC1 = 0x34;
    static constexpr uint8_t REG_DAC_DRC2 = 0x35;
    static constexpr uint8_t REG_DAC_RAMP_EQ = 0x37;
    static constexpr uint8_t REG_DACEQ = 0x38;
    static constexpr uint8_t REG_GPIO = 0x44;

    RegisterConnection& _connection;
    uint32_t _mclk_hz;
    uint32_t _fs;
    float _dvdd;

    uint8_t _read_reg(uint8_t reg);
    void _write_reg(uint8_t reg, uint8_t value);
    void _update(uint8_t reg, uint8_t mask, uint8_t value);
    void _soft_reset();
    void _power_up_analog();
    /** Solve the clock registers 0x02–0x05 for the given MCLK / fs; false if no valid set exists. */
    bool _solve_clock(uint32_t mclk_hz, uint32_t fs, uint8_t out[4]) const;
    void _write_clock(const uint8_t clk[4]);
    static uint8_t _volume_to_reg(float db);
};

/** @brief ES8311 full interface — extends ES8311Minimal with the clock tree, serial
 *  port, microphone / ADC / DAC dynamics, equalizers, routing, power control and
 *  raw register access.
 *
 * Methods that can be given invalid parameters return false instead of throwing.
 *
 * @param connection     RegisterConnection (I²C) pointing at the device.
 * @param mclk_hz        Master clock in Hz.
 * @param sample_rate_hz Audio sample rate in Hz.
 * @param dvdd_volts     DVDD supply (3.3 V or 1.8 V clock limits).
 */
class ES8311Full : public ES8311Minimal {
public:
    static constexpr uint8_t FORMAT_I2S = 0;
    static constexpr uint8_t FORMAT_LEFT_JUSTIFIED = 1;
    static constexpr uint8_t FORMAT_DSP_A = 2;
    static constexpr uint8_t FORMAT_DSP_B = 3;

    static constexpr uint8_t INPUT_ANALOG = 0;
    static constexpr uint8_t INPUT_DIGITAL = 1;
    static constexpr uint8_t INPUT_NONE = 2;

    static constexpr uint8_t ADCDAT_ADC_ADC = 0;
    static constexpr uint8_t ADCDAT_ADC_ZERO = 1;
    static constexpr uint8_t ADCDAT_ZERO_ADC = 2;
    static constexpr uint8_t ADCDAT_DAC_ADC = 4;
    static constexpr uint8_t ADCDAT_ADC_DAC = 5;
    static constexpr uint8_t ADCDAT_DAC_DAC = 6;

    explicit ES8311Full(RegisterConnection& connection, uint32_t mclk_hz = 12288000,
                        uint32_t sample_rate_hz = 48000, float dvdd_volts = 3.3f);

    /** @brief Read back the DAC volume. @return dB (−95.5 … +32). */
    float get_volume();

    /** @brief Read back the analog microphone PGA gain. @return dB (0 … 30). */
    float get_mic_gain();

    /** @brief Software reset; registers return to their reset values (reconfigure afterwards). */
    void reset();

    /** @brief Re-enable the analog, reference and clock blocks (vendor values) and set CSM_ON. */
    void power_up();

    /** @brief Standby: mute the DAC, power down the analog blocks, reset all and clear CSM_ON. */
    void power_down();

    /** @brief Set the low-power bits of register 0x0F (slightly lower THD+N / SNR).
     *  @param dac        LPDAC.
     *  @param pga        LPPGA.
     *  @param pga_out    LPPGAOUT.
     *  @param adc        LPVCMMOD (ADC).
     *  @param adc_ref    LPADCVRP.
     *  @param dac_ref    LPDACVRP.
     *  @param flash      LPFLASH.
     *  @param integrator LPINT1.
     */
    void set_low_power(bool dac = false, bool pga = false, bool pga_out = false, bool adc = false,
                       bool adc_ref = false, bool dac_ref = false, bool flash = false,
                       bool integrator = false);

    /** @brief Program the clock tree (registers 0x01–0x05) from a preset or computed dividers.
     *  @param mclk_hz        Master clock in Hz.
     *  @param sample_rate_hz Sample rate in Hz.
     *  @param mclk_from_bclk Take the master clock from the SCLK (BCLK) pin.
     *  @param mclk_invert    Invert the master clock.
     *  @return false (and no register is written) if no valid divider set exists.
     */
    bool configure_clock(uint32_t mclk_hz, uint32_t sample_rate_hz, bool mclk_from_bclk = false,
                         bool mclk_invert = false);

    /** @brief Configure the digital audio port (0x09, 0x0A, RESET.MSC, 0x06–0x08).
     *  @param format          FORMAT_I2S, FORMAT_LEFT_JUSTIFIED, FORMAT_DSP_A or FORMAT_DSP_B.
     *  @param word_length     16, 18, 20, 24 or 32 bit.
     *  @param master          true for master mode (chip drives BCLK / LRCK).
     *  @param bclk_invert     Invert BCLK.
     *  @param lrck_invert     Invert the LRCK polarity.
     *  @param dac_right       true to send the right channel to the DAC.
     *  @param bclk_div        Master BCLK divider (0 = computed from the clock configuration).
     *  @param lrck_div        Master LRCK divider (0 = computed).
     *  @param continuous_bclk Keep BCLK running when idle (master mode).
     *  @return false for an unknown format, word length or BCLK divider.
     */
    bool configure_serial(uint8_t format = FORMAT_I2S, uint8_t word_length = 16, bool master = false,
                          bool bclk_invert = false, bool lrck_invert = false, bool dac_right = false,
                          uint16_t bclk_div = 0, uint16_t lrck_div = 0, bool continuous_bclk = true);

    /** @brief Tri-state the BCLK / LRCK pins and / or ADCDAT (0x07 bits 5:4). */
    void set_tristate(bool bclk_lrck = false, bool adcdat = false);

    /** @brief Select the microphone input.
     *  @param source INPUT_ANALOG (MIC1P/MIC1N), INPUT_DIGITAL (PDM on MIC1P) or INPUT_NONE.
     *  @return false for an unknown source.
     */
    bool set_input(uint8_t source = INPUT_ANALOG);

    /** @brief Select the PDM data latch edge (0x15 bit 0). */
    void configure_dmic(bool latch_falling_edge = false);

    /** @brief Set the ADC digital volume (ALC maximum gain when the ALC is on).
     *  @param db −95.5 … +32 dB in 0.5 dB steps.
     */
    void set_adc_volume(float db);

    /** @brief Configure the ADC digital path.
     *  @param scale_db    Digital gain scale-up 0 … 42 dB in 6 dB steps.
     *  @param osr         Oversampling ratio in × fs (single speed 60–252, double speed 32–126).
     *  @param double_speed Select the double-speed ADC.
     *  @param hpf         true for the dynamic high-pass filter, false to freeze the DC offset.
     *  @param hpf_coeff1  Stage-1 high-pass coefficient 0–31.
     *  @param hpf_coeff2  Stage-2 high-pass coefficient 0–31.
     *  @return false if osr is out of range.
     */
    bool configure_adc(uint8_t scale_db = 24, uint16_t osr = 64, bool double_speed = false,
                       bool hpf = true, uint8_t hpf_coeff1 = 0x0C, uint8_t hpf_coeff2 = 0x0C);

    /** @brief Set the ADC volume soft-ramp rate (0 = off, 1–15 = 0.25 dB per 2^(rate+1) LRCK). */
    void set_adc_ramp(uint8_t rate);

    /** @brief Configure the automatic level control, noise gate and automute (0x18–0x1A).
     *  @param enabled         Enable the ALC.
     *  @param max_level_db    Upper target level (rounded to the nearest of 16 levels).
     *  @param min_level_db    Lower target level.
     *  @param window          ALC window size 0–15.
     *  @param automute        Enable automute.
     *  @param automute_window Automute detection window 0–15 (2048 × (n+1) samples).
     *  @param noise_gate_db   Noise gate threshold (rounded to the nearest of 16).
     */
    void configure_alc(bool enabled, float max_level_db = -6.0f, float min_level_db = -30.1f,
                       uint8_t window = 0, bool automute = false, uint8_t automute_window = 0,
                       float noise_gate_db = -96.0f);

    /** @brief Bypass the ADC equalizer (0x1C bit 6). */
    void bypass_adc_eq();

    /** @brief Load the ADC equalizer coefficients and enable the equalizer.
     *  @param coefficients Five 30-bit values (b0, a1, a2, b1, b2).
     *  @return false if a coefficient exceeds 30 bits.
     */
    bool set_adc_eq(const uint32_t coefficients[5]);

    /** @brief Set the DAC volume / DRC soft-ramp rate (0x37 bits 7:4). */
    void set_dac_ramp(uint8_t rate);

    /** @brief Configure the DAC dynamic range control (0x34, 0x35).
     *  @param enabled      Enable the DRC (the DAC volume becomes its maximum gain).
     *  @param max_level_db Upper target level.
     *  @param min_level_db Lower target level.
     *  @param window       Window size 0–15.
     */
    void configure_drc(bool enabled, float max_level_db = -30.1f, float min_level_db = -30.1f,
                       uint8_t window = 0);

    /** @brief Bypass the DAC equalizer (0x37 bit 3). */
    void bypass_dac_eq();

    /** @brief Load the DAC equalizer coefficients and enable the equalizer.
     *  @param coefficients Three 30-bit values (b0, b1, a1).
     *  @return false if a coefficient exceeds 30 bits.
     */
    bool set_dac_eq(const uint32_t coefficients[3]);

    /** @brief Set the three DAC mute bits individually (0x31 bits 7:5). */
    void set_dac_mute_options(bool dsm = true, bool dem = true, bool dsm_target = false);

    /** @brief Feed the ADC output to the DAC (0x44 bit 7). */
    void set_loopback(bool enabled);

    /** @brief Select the ADCDAT content (0x44 bits 6:4).
     *  @param mode ADCDAT_ADC_ADC, ADCDAT_ADC_ZERO, ADCDAT_ZERO_ADC, ADCDAT_DAC_ADC,
     *              ADCDAT_ADC_DAC or ADCDAT_DAC_DAC.
     *  @return false for an unknown mode.
     */
    bool set_adcdat_mode(uint8_t mode);

    /** @brief Mute the digital audio input and / or output (0x09 / 0x0A bit 6). */
    void set_sdp_mute(bool input = false, bool output = false);

    /** @brief Raw register read. */
    uint8_t read_register(uint8_t reg);

    /** @brief Raw register write. */
    void write_register(uint8_t reg, uint8_t value);

private:
    uint32_t _internal_clock();
    bool _write_eq(uint8_t base, const uint32_t* coefficients, uint8_t count);
};
