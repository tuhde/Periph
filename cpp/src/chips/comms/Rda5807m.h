#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

/** @brief RDA5807M single-chip FM stereo radio tuner — minimal interface.
 *
 * Tunes to a station, adjusts volume, mutes, and seeks the next station.
 * No configuration required beyond the transport.
 *
 * Unlike most chips in this project, the RDA5807M has no register-pointer
 * byte: writes always start at the fixed register 0x02 and reads always
 * start at the fixed register 0x0A. This driver keeps an in-memory shadow
 * of registers 0x02-0x07 (6 big-endian 16-bit words) and rewrites all of
 * them on every change, since the chip cannot be told to start a write
 * anywhere else.
 *
 * @param transport      I²C transport bound to address 0x10.
 * @param frequency_mhz  Initial frequency in MHz (default 100.0).
 * @param volume         Initial volume, 0 (mute) to 15 (max) (default 8).
 */
class RDA5807MMinimal {
public:
    static constexpr uint8_t BAND_US_EUROPE = 0;
    static constexpr uint8_t BAND_JAPAN = 1;
    static constexpr uint8_t BAND_WORLD = 2;
    static constexpr uint8_t BAND_EAST_EUROPE = 3;

    static constexpr uint8_t SPACE_100K = 0;
    static constexpr uint8_t SPACE_200K = 1;
    static constexpr uint8_t SPACE_50K = 2;
    static constexpr uint8_t SPACE_25K = 3;

    RDA5807MMinimal(Transport& transport, float frequency_mhz = 100.0f, uint8_t volume = 8);

    /** @brief Tune to a frequency, blocking until the tune completes.
     *  @param frequency_mhz Target frequency in MHz.
     */
    void set_frequency(float frequency_mhz);

    /** @brief Read the currently tuned frequency.
     *  @return Frequency in MHz, derived from READCHAN.
     */
    float frequency();

    /** @brief Set the output volume.
     *  @param level Volume 0 (mute) to 15 (max), logarithmic scale.
     */
    void set_volume(uint8_t level);

    /** @brief Mute or unmute the audio output.
     *  @param enable true to mute, false for normal operation.
     */
    void mute(bool enable);

    /** @brief Seek to the next station, blocking until the seek completes.
     *  @param up            true to seek upward, false to seek downward.
     *  @param[out] frequency_mhz  New frequency in MHz if a station was found.
     *  @return true if a station was found (SF flag clear), false on seek failure.
     */
    bool seek(bool up, float& frequency_mhz);

protected:
    static constexpr uint16_t _DHIZ = 0x8000;
    static constexpr uint16_t _DMUTE = 0x4000;
    static constexpr uint16_t _MONO = 0x2000;
    static constexpr uint16_t _BASS = 0x1000;
    static constexpr uint16_t _SEEKUP = 0x0200;
    static constexpr uint16_t _SEEK = 0x0100;
    static constexpr uint16_t _SKMODE = 0x0080;
    static constexpr uint16_t _RDS_EN = 0x0008;
    static constexpr uint16_t _NEW_METHOD = 0x0004;
    static constexpr uint16_t _SOFT_RESET = 0x0002;
    static constexpr uint16_t _ENABLE = 0x0001;

    static constexpr uint16_t _TUNE = 0x0010;

    static constexpr uint16_t _DE = 0x0800;
    static constexpr uint16_t _SOFTMUTE_EN = 0x0200;
    static constexpr uint16_t _AFCD = 0x0100;

    static constexpr uint16_t _INT_MODE = 0x8000;

    static constexpr uint16_t _BAND_65M_50M = 0x0200;

    static constexpr uint16_t _RDSR = 0x8000;
    static constexpr uint16_t _STC = 0x4000;
    static constexpr uint16_t _SF = 0x2000;
    static constexpr uint16_t _ST = 0x0400;

    static constexpr uint16_t _FM_TRUE = 0x0100;
    static constexpr uint16_t _FM_READY = 0x0080;

    Transport& _transport;
    uint16_t _regs[6];
    uint8_t _band;
    uint8_t _space;
    bool _east_europe_50m;
    float _current_freq;

    static uint16_t _freq_to_chan(uint8_t band, uint8_t space, bool east_europe_50m, float frequency_mhz);
    static float _chan_to_freq(uint8_t band, uint8_t space, bool east_europe_50m, uint16_t chan);

    void _write_regs();
    void _read_status(uint16_t* words, uint8_t count);
    uint16_t _wait_stc();
};

/** @brief RDA5807M full interface — extends RDA5807MMinimal with band/spacing
 *  configuration, RDS, status, and power management.
 *
 * @param transport      I²C transport bound to address 0x10.
 * @param frequency_mhz  Initial frequency in MHz (default 100.0).
 * @param volume         Initial volume, 0 (mute) to 15 (max) (default 8).
 */
class RDA5807MFull : public RDA5807MMinimal {
public:
    RDA5807MFull(Transport& transport, float frequency_mhz = 100.0f, uint8_t volume = 8);

    /** @brief Reconfigure tuner-level settings.
     *
     * Only parameters passed with a non-negative/valid sentinel are changed
     * (see per-parameter defaults). Changing band or space re-tunes to the
     * current frequency, since CHAN's meaning depends on both.
     *
     *  @param band            BAND_US_EUROPE, BAND_JAPAN, BAND_WORLD, or BAND_EAST_EUROPE, or 0xFF to leave unchanged.
     *  @param space           SPACE_100K, SPACE_200K, SPACE_50K, or SPACE_25K, or 0xFF to leave unchanged.
     *  @param de_emphasis     true for 50 µs, false for 75 µs.
     *  @param seek_threshold  Seek SNR threshold, 0-15 (default 8, ~32 dB).
     *  @param seek_mode       true to stop seeking at the band limit, false to wrap.
     *  @param clk_mode        Reference clock select, 0-7.
     *  @param afc_disable     true to disable AFC.
     *  @param east_europe_50m When band is BAND_EAST_EUROPE, true selects 65-76 MHz (default), false selects the 50 MHz-based sub-band.
     */
    void configure(uint8_t band = 0xFF, uint8_t space = 0xFF, int8_t de_emphasis = -1,
                   int16_t seek_threshold = -1, int8_t seek_mode = -1, int16_t clk_mode = -1,
                   int8_t afc_disable = -1, int8_t east_europe_50m = -1);

    /** @brief Enable or disable bass boost.
     *  @param enable true to enable bass boost.
     */
    void set_bass_boost(bool enable);

    /** @brief Force mono or allow stereo.
     *  @param enable true to force mono, false to allow stereo.
     */
    void set_mono(bool enable);

    /** @brief Enable or disable soft mute.
     *  @param enable true to enable soft mute (chip default).
     */
    void set_softmute(bool enable);

    /** @brief Enable or disable the RDS/RBDS decoder.
     *  @param enable true to enable RDS/RBDS.
     */
    void enable_rds(bool enable);

    /** @brief Check whether a new RDS/RBDS group is available.
     *  @return true if RDSR is set.
     */
    bool rds_ready();

    /** @brief Read the four raw RDS/RBDS blocks, if a new group is ready.
     *
     * Does not decode group content (PI, PS, RadioText, ...) — the caller
     * interprets the raw blocks per the RDS/RBDS standard.
     *
     *  @param[out] block_a  Raw RDS block A.
     *  @param[out] block_b  Raw RDS block B.
     *  @param[out] block_c  Raw RDS block C.
     *  @param[out] block_d  Raw RDS block D.
     *  @return true if a new group was ready and the blocks were filled in.
     */
    bool read_rds_group(uint16_t& block_a, uint16_t& block_b, uint16_t& block_c, uint16_t& block_d);

    /** @brief Check the stereo indicator.
     *  @return true if the current station is being received in stereo.
     */
    bool is_stereo();

    /** @brief Check whether the current channel is a real station.
     *  @return true if FM_TRUE is set.
     */
    bool is_station();

    /** @brief Check whether the tuner is ready.
     *  @return true if FM_READY is set.
     */
    bool is_ready();

    /** @brief Read the received signal strength indicator.
     *  @return Raw RSSI, 0 (weakest) to 127 (strongest), logarithmic. No
     *  absolute dBµV mapping is published by the datasheet.
     */
    uint8_t signal_strength();

    /** @brief Power the chip down or up.
     *
     * Powering back up clears the tuner's PLL lock, so waking from standby
     * blocks briefly for the chip to recover, then re-tunes to the last
     * known frequency (mirroring the datasheet's power-up sequencing, which
     * the chip otherwise never recovers from on its own).
     *
     *  @param enable true to power down, false to power up.
     */
    void standby(bool enable);

    /** @brief Pulse the soft-reset bit, then re-apply the current configuration.
     *
     * A soft reset restores the chip's power-on register defaults and clears
     * the tuner's PLL lock, so this blocks briefly for the chip to recover,
     * then re-tunes to the last known frequency (the chip never reacquires
     * lock on its own otherwise).
     */
    void soft_reset();
};
