#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"
#include "../../connection/OutputPin.h"

/**
 * @file CS43L22.h
 * @brief CS43L22 low-power stereo DAC with headphone and Class-D speaker amplifiers (Cirrus Logic).
 *
 * Configured entirely through the I²C control port (0x4A with AD0 = DGND, 0x4B with AD0 = VL,
 * 100 kHz maximum). Audio data travels over a separate I²S / PCM port that this driver does not
 * touch: the application supplies MCLK and the stream, calls start() once the clocks run and
 * stop() before they go away. Registers are accessed through a RegisterConnection with a
 * one-byte register address and are written one at a time.
 *
 * Methods that can be given invalid parameters return false (and write nothing) instead of
 * throwing, so the driver builds on Arduino AVR without exceptions.
 *
 * See specs/adc_dac/cs43l22.md.
 */

/** @brief Clocking settings returned by CS43L22Full::clockingFor (arguments of setClocking). */
struct CS43L22Clocking {
    bool autoDetect;
    uint8_t speed;
    bool group32k;
    bool videoClk;
    uint8_t ratio;
    bool mclkDiv2;
};

/** @brief Decoded STATUS register (reading it clears the flags). */
struct CS43L22Status {
    bool clockError;
    bool dspAOverflow;
    bool dspBOverflow;
    bool pcmAOverflow;
    bool pcmBOverflow;
};

/** @brief Decoded SPEAKER_STATUS register. */
struct CS43L22SpeakerStatus {
    bool speakerAShort;
    bool speakerBShort;
    bool spkrHpPin;
};

/**
 * @brief CS43L22 stereo DAC — minimal interface.
 *
 * Defaults: I²S slave, 24-bit, clock speed auto-detected from the MCLK / LRCK ratio, headphone
 * amplifiers always on, speaker amplifiers off, headphone gain 0.6047, master volume 0 dB,
 * digital soft ramp on. init() resets the chip (when a RESET pin is given), checks the ID, loads
 * these defaults and runs the required initialization; the chip stays powered down until start().
 */
class CS43L22Minimal {
public:
    static constexpr uint8_t I2C_ADDRESS = 0x4A;

    /**
     * @param connection RegisterConnection (I²C) pointing at the device (0x4A / 0x4B).
     * @param resPin     Optional OutputPin wired to the active-low RESET pin.
     */
    explicit CS43L22Minimal(RegisterConnection& connection, OutputPin* resPin = nullptr);

    /** @brief Reset, verify the chip ID, load the defaults and run the required initialization.
     *  @return false if the chip ID (register 0x01, bits 7:3) is not 0x1C. */
    bool init();

    /** @brief Power the chip up (POWER_CTL_1 = 0x9E). Call after MCLK, SCLK and LRCK run. */
    void start();

    /** @brief Power-down sequence: mute, disable soft ramp / zero-cross, POWER_CTL_1 = 0x9F,
     *  wait 100 µs. MCLK may be removed after it returns. */
    void stop();

    /** @brief Set the master volume of both channels.
     *  @param db −102 … +12 dB, nearest 0.5 dB step (clamped). */
    void setVolume(float db);

    /** @brief Master volume of channel A in dB. */
    float getVolume();

    /** @brief Mute or unmute both headphone channels. @param on true to mute. */
    void mute(bool on = true);

protected:
    static constexpr uint8_t REG_ID = 0x01;
    static constexpr uint8_t REG_POWER_CTL_1 = 0x02;
    static constexpr uint8_t REG_POWER_CTL_2 = 0x04;
    static constexpr uint8_t REG_CLOCKING_CTL = 0x05;
    static constexpr uint8_t REG_INTERFACE_CTL_1 = 0x06;
    static constexpr uint8_t REG_INTERFACE_CTL_2 = 0x07;
    static constexpr uint8_t REG_PASSTHRU_A_SEL = 0x08;
    static constexpr uint8_t REG_PASSTHRU_B_SEL = 0x09;
    static constexpr uint8_t REG_ANALOG_ZC_SR = 0x0A;
    static constexpr uint8_t REG_PLAYBACK_CTL_1 = 0x0D;
    static constexpr uint8_t REG_MISC_CTL = 0x0E;
    static constexpr uint8_t REG_PLAYBACK_CTL_2 = 0x0F;
    static constexpr uint8_t REG_PASSTHRU_A_VOL = 0x14;
    static constexpr uint8_t REG_PASSTHRU_B_VOL = 0x15;
    static constexpr uint8_t REG_PCM_A_VOL = 0x1A;
    static constexpr uint8_t REG_PCM_B_VOL = 0x1B;
    static constexpr uint8_t REG_BEEP_FREQ_ON = 0x1C;
    static constexpr uint8_t REG_BEEP_VOL_OFF = 0x1D;
    static constexpr uint8_t REG_BEEP_TONE_CFG = 0x1E;
    static constexpr uint8_t REG_TONE_CTL = 0x1F;
    static constexpr uint8_t REG_MASTER_A_VOL = 0x20;
    static constexpr uint8_t REG_MASTER_B_VOL = 0x21;
    static constexpr uint8_t REG_HP_A_VOL = 0x22;
    static constexpr uint8_t REG_HP_B_VOL = 0x23;
    static constexpr uint8_t REG_SPK_A_VOL = 0x24;
    static constexpr uint8_t REG_SPK_B_VOL = 0x25;
    static constexpr uint8_t REG_PCM_MIXER_SWAP = 0x26;
    static constexpr uint8_t REG_LIMIT_CTL_1 = 0x27;
    static constexpr uint8_t REG_LIMIT_CTL_2 = 0x28;
    static constexpr uint8_t REG_LIMIT_ATTACK = 0x29;
    static constexpr uint8_t REG_STATUS = 0x2E;
    static constexpr uint8_t REG_BATTERY_COMP = 0x2F;
    static constexpr uint8_t REG_VP_LEVEL = 0x30;
    static constexpr uint8_t REG_SPEAKER_STATUS = 0x31;
    static constexpr uint8_t REG_CHARGE_PUMP_FREQ = 0x34;
    static constexpr uint8_t REG_INIT_00 = 0x00;
    static constexpr uint8_t REG_INIT_32 = 0x32;
    static constexpr uint8_t REG_INIT_47 = 0x47;

    RegisterConnection& _connection;
    OutputPin* _resPin;

    uint8_t _read(uint8_t reg);
    void _write(uint8_t reg, uint8_t value);
    void _update(uint8_t reg, uint8_t mask, uint8_t value);
    void _setBit(uint8_t reg, uint8_t bit, bool on);
    static uint8_t _halfDbCode(float db, float lo, float hi);
    static uint8_t _limitedVolumeCode(float db);
    static void _delayMs(unsigned ms);
};

/**
 * @brief CS43L22 stereo DAC — full interface.
 *
 * Adds per-channel volumes, output power selection, clocking and serial interface configuration,
 * tone control, beep generator, peak limiter, soft ramp / zero-cross, analog passthrough, battery
 * compensation, status reads and raw register access.
 */
class CS43L22Full : public CS43L22Minimal {
public:
    static constexpr uint8_t OUT_FOLLOW_LOW = 0;
    static constexpr uint8_t OUT_FOLLOW_HIGH = 1;
    static constexpr uint8_t OUT_ON = 2;
    static constexpr uint8_t OUT_OFF = 3;
    static constexpr uint8_t FMT_LJ = 0;
    static constexpr uint8_t FMT_I2S = 1;
    static constexpr uint8_t FMT_RJ = 2;
    static constexpr uint8_t BEEP_OFF = 0;
    static constexpr uint8_t BEEP_SINGLE = 1;
    static constexpr uint8_t BEEP_MULTIPLE = 2;
    static constexpr uint8_t BEEP_CONTINUOUS = 3;
    static constexpr uint8_t SRC_NORMAL = 0;
    static constexpr uint8_t SRC_MIX = 1;
    static constexpr uint8_t SRC_SWAPPED = 3;
    static constexpr uint8_t GAIN_0_3959 = 0;
    static constexpr uint8_t GAIN_0_4571 = 1;
    static constexpr uint8_t GAIN_0_5111 = 2;
    static constexpr uint8_t GAIN_0_6047 = 3;
    static constexpr uint8_t GAIN_0_7099 = 4;
    static constexpr uint8_t GAIN_0_8399 = 5;
    static constexpr uint8_t GAIN_1_000 = 6;
    static constexpr uint8_t GAIN_1_143 = 7;

    explicit CS43L22Full(RegisterConnection& connection, OutputPin* resPin = nullptr);

    /** @brief Read chip ID (0x1C) and revision. */
    void readId(uint8_t& chipId, uint8_t& revision);
    /** @brief Pulse RESET and re-run init(). @return false if no RESET pin or the ID check fails. */
    bool reset();
    /** @brief Output amplifier power modes (OUT_*), applied to both channels. @return false if a mode > 3. */
    bool setOutputs(uint8_t headphone, uint8_t speaker);
    /** @brief Independent master volume A / B in dB (−102 … +12). */
    void setVolumeChannels(float leftDb, float rightDb);
    /** @brief Headphone volume 0 … −96 dB, below −96 dB muted. Pass the same value twice for both channels. */
    void setHeadphoneVolume(float leftDb, float rightDb);
    /** @brief Speaker volume 0 … −96 dB, below −96 dB muted. */
    void setSpeakerVolume(float leftDb, float rightDb);
    /** @brief PCM volume −51.5 … +12 dB (mute bits kept). */
    void setPcmVolume(float leftDb, float rightDb);
    /** @brief Mute or unmute the PCM channels. */
    void mutePcm(bool left, bool right);
    /** @brief Headphone analog gain index 0–7 (GAIN_*). @return false if index > 7. */
    bool setHeadphoneGain(uint8_t index);
    /** @brief Headphone analog gain multiplier (0.3959 … 1.143). */
    float headphoneGain();
    /** @brief Mute outputs: 1 mute, 0 unmute, -1 keep, per output. */
    void muteOutputs(int8_t headphone, int8_t speaker);
    /** @brief Speaker mono / swap / exact-50 % mute. */
    void setSpeakerMode(bool mono, bool swap, bool mute5050 = false);
    /** @brief Write CLOCKING_CTL. @return false if speed or ratio > 3. */
    bool setClocking(bool autoDetect = true, uint8_t speed = 1, bool group32k = false,
                     bool videoClk = false, uint8_t ratio = 0, bool mclkDiv2 = false);
    /** @brief Datasheet Table 1 settings for an MCLK / sample-rate pair. @return false if not listed. */
    static bool clockingFor(uint32_t mclkHz, float fsHz, CS43L22Clocking& out);
    /** @brief Serial port configuration. @return false on an invalid combination (DSP needs FMT_LJ). */
    bool setInterface(bool master = false, uint8_t fmt = FMT_I2S, uint8_t wordLength = 0, bool dsp = false,
                      bool invertSclk = false, bool sclkEqMclk = false, bool invertSwitch = false);
    /** @brief Treble / bass shelves, −10.5 … +12 dB in 1.5 dB steps; corners 0–3. */
    bool setTone(float trebleDb, float bassDb, uint8_t trebleCorner = 0, uint8_t bassCorner = 0,
                 bool enabled = true);
    /** @brief Program the beep generator (timing first, mode last).
     *  @return false on a bad argument, or when a beep is active and mode is not BEEP_OFF. */
    bool setBeep(uint8_t frequency, uint8_t onTime, uint8_t offTime, float volumeDb, uint8_t mode,
                 bool mix = true);
    /** @brief Stop the beep generator. */
    void stopBeep();
    /** @brief Peak limiter. @return false if release or attack > 63. */
    bool setLimiter(bool enabled, int maxDb = 0, int cushionDb = 0, uint8_t release = 0x3F, uint8_t attack = 0,
                    bool limitAll = true, bool softRampDisable = false, bool zeroCrossDisable = false);
    /** @brief Digital and analog soft ramp. */
    void setSoftRamp(bool digital, bool analogLeft = false, bool analogRight = false);
    /** @brief Digital and analog zero-cross. */
    void setZeroCross(bool digital, bool analogLeft = false, bool analogRight = false);
    /** @brief Digital de-emphasis filter. */
    void setDeemphasis(bool enabled);
    /** @brief PCM channel sources (SRC_*). @return false if a source > 3. */
    bool setPcmSwap(uint8_t leftSource, uint8_t rightSource);
    /** @brief Invert PCM polarity per channel. */
    void invertPcm(bool left, bool right);
    /** @brief Route analog inputs AIN1…AIN4 to the outputs. @return false if an input is not 1–4. */
    bool setPassthrough(bool enabled, uint8_t leftInput = 1, uint8_t rightInput = 1);
    /** @brief Passthrough volume −60 … +12 dB. */
    void setPassthroughVolume(float leftDb, float rightDb);
    /** @brief Mute the passthrough channels. */
    void mutePassthrough(bool left, bool right);
    /** @brief Hold (true) or apply (false) register changes. */
    void freeze(bool on);
    /** @brief Battery compensation. @return false if vpRefIndex > 15. */
    bool setBatteryCompensation(bool enabled, uint8_t vpRefIndex = 0, bool monitor = false);
    /** @brief VP supply voltage in volts: VP_LEVEL × vaVolts / 63.3. */
    float readVpVoltage(float vaVolts);
    /** @brief Read the status register (read clears the flags). */
    CS43L22Status readStatus();
    /** @brief Read the speaker status register. */
    CS43L22SpeakerStatus readSpeakerStatus();
    /** @brief Charge-pump divider N (0–15); frequency = 64·Fs / (N + 2). @return false if n > 15. */
    bool setChargePumpFrequency(uint8_t n);
    /** @brief Read a documented register. @return false if the register is reserved. */
    bool readRegister(uint8_t reg, uint8_t& value);
    /** @brief Write a documented, writable register. @return false if reserved or read-only. */
    bool writeRegister(uint8_t reg, uint8_t value);
};
