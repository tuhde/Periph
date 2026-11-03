#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief DRV2605 haptic driver for ERM and LRA actuators — minimal interface.
 *
 *  Plays the built-in 123-effect TS2200 ROM library. The constructor runs the
 *  initialization sequence: DEV_RESET, DEVICE_ID check, leave standby, and
 *  actuator selection (ERM: library A, open loop; LRA: LRA library, closed
 *  loop with auto-resonance). The I²C address is fixed at 0x5A and the EN pin
 *  must be high for register access.
 *
 *  Check isReady() after construction — it is false if the reset did not
 *  complete or DEVICE_ID was not 3.
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device.
 *  @param lra        false for an ERM, true for an LRA.
 */
class DRV2605Minimal {
public:
    /** @brief Fixed 7-bit I²C address. */
    static constexpr uint8_t I2C_ADDRESS = 0x5A;
    /** @brief Expected DEVICE_ID for the DRV2605. */
    static constexpr uint8_t DEVICE_ID = 3;

    /** @name Named ROM effect identifiers (TS2200 library). @{ */
    static constexpr uint8_t EFFECT_STRONG_CLICK   = 1;
    static constexpr uint8_t EFFECT_SHARP_CLICK    = 4;
    static constexpr uint8_t EFFECT_SOFT_BUMP      = 7;
    static constexpr uint8_t EFFECT_DOUBLE_CLICK   = 10;
    static constexpr uint8_t EFFECT_TRIPLE_CLICK   = 12;
    static constexpr uint8_t EFFECT_STRONG_BUZZ    = 14;
    static constexpr uint8_t EFFECT_ALERT_750MS    = 15;
    static constexpr uint8_t EFFECT_ALERT_1000MS   = 16;
    static constexpr uint8_t EFFECT_BUZZ           = 47;
    static constexpr uint8_t EFFECT_PULSING_STRONG = 52;
    static constexpr uint8_t EFFECT_LONG_BUZZ      = 118;
    /** @} */

    /** @name MODE register values. @{ */
    static constexpr uint8_t MODE_INTERNAL_TRIGGER = 0;
    static constexpr uint8_t MODE_EXTERNAL_EDGE    = 1;
    static constexpr uint8_t MODE_EXTERNAL_LEVEL   = 2;
    static constexpr uint8_t MODE_PWM_ANALOG       = 3;
    static constexpr uint8_t MODE_AUDIO_TO_VIBE    = 4;
    static constexpr uint8_t MODE_RTP              = 5;
    static constexpr uint8_t MODE_DIAGNOSTICS      = 6;
    static constexpr uint8_t MODE_AUTO_CALIBRATION = 7;
    /** @} */

    DRV2605Minimal(RegisterConnection& connection, bool lra = false);

    /** @brief True if the init sequence succeeded (reset completed, DEVICE_ID = 3). */
    bool isReady() const { return _ready; }

    /** @brief Play one ROM effect and return immediately.
     *
     *  Writes the effect to sequencer slot 1 and 0 (end) to slot 2, switches to
     *  internal-trigger mode if needed, then sets GO.
     *
     *  @param effectId Effect identifier 1–123.
     *  @return true if started, false if @p effectId was out of range (no write). */
    bool playEffect(uint8_t effectId);

    /** @brief Cancel playback by clearing the GO bit. */
    virtual void stop();

    /** @brief True while a sequence is playing (GO bit set). */
    bool isPlaying();

    virtual ~DRV2605Minimal() {}

protected:
    static constexpr uint8_t REG_STATUS        = 0x00;
    static constexpr uint8_t REG_MODE          = 0x01;
    static constexpr uint8_t REG_RTP_INPUT     = 0x02;
    static constexpr uint8_t REG_LIBRARY       = 0x03;
    static constexpr uint8_t REG_SEQ1          = 0x04;
    static constexpr uint8_t REG_GO            = 0x0C;
    static constexpr uint8_t REG_ODT           = 0x0D;
    static constexpr uint8_t REG_ATH_CTRL      = 0x11;
    static constexpr uint8_t REG_RATED_VOLTAGE = 0x16;
    static constexpr uint8_t REG_OD_CLAMP      = 0x17;
    static constexpr uint8_t REG_A_CAL_COMP    = 0x18;
    static constexpr uint8_t REG_FEEDBACK      = 0x1A;
    static constexpr uint8_t REG_CONTROL1      = 0x1B;
    static constexpr uint8_t REG_CONTROL2      = 0x1C;
    static constexpr uint8_t REG_CONTROL3      = 0x1D;
    static constexpr uint8_t REG_CONTROL4      = 0x1E;
    static constexpr uint8_t REG_VBAT          = 0x21;
    static constexpr uint8_t REG_LRA_PERIOD    = 0x22;

    static constexpr uint8_t MODE_DEV_RESET = 0x80;
    static constexpr uint8_t MODE_STANDBY   = 0x40;

    RegisterConnection& _connection;
    uint8_t _mode = 0;
    bool _standby = false;
    bool _ready = false;

    void _writeReg(uint8_t reg, uint8_t value);
    uint8_t _readReg(uint8_t reg);
    void _updateReg(uint8_t reg, uint8_t mask, uint8_t value);
    void _writeMode(uint8_t mode, bool standby = false);
    bool _resetChip();
    void _ensureInternal();
    static uint8_t _clampCode(float value);
};

/** @brief DRV2605 full interface — extends Minimal with the complete chip functionality.
 *
 *  Adds the waveform sequencer, real-time playback, mode and library control,
 *  drive configuration, auto-calibration, diagnostics, audio-to-vibe, status
 *  readout and one-time OTP programming. Setters that validate their input
 *  return false (without writing) when an argument is out of range.
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device.
 *  @param lra        false for an ERM, true for an LRA.
 */
class DRV2605Full : public DRV2605Minimal {
public:
    /** @brief One step of a waveform sequence. */
    struct SequenceStep {
        bool isWait;       ///< false = ROM effect, true = delay
        uint16_t value;    ///< effect id 1–123, or delay in ms (0–1270, 10 ms steps)
        /** @brief A ROM-effect step. */
        static SequenceStep effect(uint8_t id) { return SequenceStep{false, id}; }
        /** @brief A delay step. */
        static SequenceStep wait(uint16_t ms) { return SequenceStep{true, ms}; }
    };

    /** @brief Decoded STATUS register (reading clears the latched flags). */
    struct Status {
        bool diagFailed;   ///< Last calibration/diagnostic failed
        bool fbTimeout;    ///< Feedback controller timed out
        bool overTemp;     ///< Overtemperature shutdown latched
        bool overCurrent;  ///< Overcurrent latched
    };

    /** @brief Auto-calibration result and stored calibration values. */
    struct Calibration {
        bool ok;           ///< Calibration converged (always true from getCalibration())
        uint8_t comp;      ///< A_CAL_COMP
        uint8_t bemf;      ///< A_CAL_BEMF
        uint8_t bemfGain;  ///< BEMF_GAIN (0–3)
    };

    DRV2605Full(RegisterConnection& connection, bool lra = false);

    /** @brief Play up to 8 effects/delays.
     *  @param items Steps (effect id 1–123 or delay 0–1270 ms).
     *  @param count Number of steps, 1–8.
     *  @return false if @p count or a step is out of range (no write). */
    bool playSequence(const SequenceStep* items, size_t count);

    /** @brief Poll GO until playback finishes.
     *  @param timeoutMs Give-up time in ms.
     *  @return true if finished, false on timeout. */
    bool waitDone(uint32_t timeoutMs = 5000);

    /** @brief Cancel playback; also leaves real-time-playback mode (output to zero). */
    void stop() override;

    /** @brief Select the ROM library (0 empty, 1–5 ERM A–E, 6 LRA). @return false if out of range. */
    bool setLibrary(uint8_t library);
    /** @brief Set MODE<2:0> (0–7) and leave standby. @return false if out of range. */
    bool setMode(uint8_t mode);
    /** @brief Enter software standby (also stops playback). */
    void standby();
    /** @brief Leave software standby. */
    void wake();
    /** @brief DEV_RESET and wait for completion; re-run init logic afterwards. @return false if the reset timed out. */
    bool reset();
    /** @brief Put the outputs in true high-impedance state. */
    void setHiZ(bool enabled);
    /** @brief Select ERM (false) or LRA (true); run calibrate() afterwards. */
    void setActuator(bool lra);
    /** @brief Open-loop drive (ERM_OPEN_LOOP or LRA_OPEN_LOOP by actuator type). */
    void setOpenLoop(bool enabled);
    /** @brief Bidirectional (true) or unidirectional (false) input.
     *  @return false (no write) if unidirectional is requested in open loop. */
    bool setBidirectional(bool enabled);

    /** @brief Stream a signed RTP amplitude; enters RTP mode (leave with stop()).
     *  @param level 0.0–1.0 (mapped to 0–127). @return false if out of range. */
    bool rtpLevel(float level);
    /** @brief Write a raw RTP_INPUT byte and enter RTP mode. */
    void rtpWrite(uint8_t value);
    /** @brief DATA_FORMAT_RTP: unsigned (true) or signed (false). */
    void setRtpFormat(bool isUnsigned);
    /** @brief N_PWM_ANALOG: analog (true) or PWM (false) input for mode 3. */
    void setInput(bool analog);

    /** @brief Set RATED_VOLTAGE from volts.
     *  @param volts Rated voltage in V (RMS for an LRA).
     *  @param lraFreqHz LRA resonance in Hz; required (> 0) for an LRA.
     *  @return false if the arguments cannot be converted (no write). */
    bool setRatedVoltage(float volts, float lraFreqHz = 0.0f);
    /** @brief Set RATED_VOLTAGE from a raw code. */
    void setRatedVoltageCode(uint8_t code);
    /** @brief Set OD_CLAMP from volts.
     *  @param volts Clamp voltage in V (RMS for LRA open loop).
     *  @param lraFreqHz LRA frequency in Hz; required for LRA open loop.
     *  @return false for ERM closed loop (needs a raw code) or a missing frequency. */
    bool setOverdriveClamp(float volts, float lraFreqHz = 0.0f);
    /** @brief Set OD_CLAMP from a raw code. */
    void setOverdriveClampCode(uint8_t code);
    /** @brief DRIVE_TIME in ms (LRA: half the resonance period; ERM: back-EMF sample period). */
    void setDriveTime(float ms);
    /** @brief FEEDBACK_CONTROL fields. @return false if an argument is out of range. */
    bool setFeedback(uint8_t brakeFactor = 3, uint8_t loopGain = 1, uint8_t bemfGain = 2);
    /** @brief CONTROL1–CONTROL3 fields not covered elsewhere. @return false if an argument is out of range. */
    bool setAdvanced(bool startupBoost = true, bool brakeStabilizer = true, uint8_t sampleTime = 3,
                     uint8_t blankingTime = 1, uint8_t idissTime = 1, bool lraDriveTwice = false,
                     bool supplyComp = true, uint8_t noiseGate = 2);
    /** @brief ODT/SPT/SNT/BRT offsets in ms (5 ms steps, signed 8-bit). @return false if out of range. */
    bool setTimeOffsets(int16_t overdriveMs = 0, int16_t sustainPosMs = 0, int16_t sustainNegMs = 0,
                        int16_t brakeMs = 0);

    /** @brief Run the auto-calibration procedure.
     *  @param lra true for an LRA. @param ratedVoltage Rated V. @param overdriveVoltage Clamp V
     *         (ERM: open-loop conversion). @param lraFreqHz Required (> 0) for an LRA.
     *  @return Calibration{ok, comp, bemf, bemfGain}; ok is false on failure or bad arguments. */
    Calibration calibrate(bool lra, float ratedVoltage, float overdriveVoltage, float lraFreqHz = 0.0f);
    /** @brief Read the stored A_CAL_COMP, A_CAL_BEMF and BEMF_GAIN. */
    Calibration getCalibration();
    /** @brief Restore calibration values. @return false if an argument is out of range. */
    bool setCalibration(uint8_t comp, uint8_t bemf, uint8_t bemfGain);
    /** @brief Run actuator diagnostics. @return true if the actuator is OK. */
    bool diagnose();
    /** @brief Configure audio-to-vibe and enter that mode.
     *  @return false if @p peakTimeMs (10/20/30/40) or @p filterHz (100/125/150/200) is invalid or a level is out of range. */
    bool setAudioToVibe(bool acCouple = true, uint8_t peakTimeMs = 20, uint8_t filterHz = 125,
                        float minInputV = 0.1f, float maxInputV = 1.8f, float minDrive = 0.1f,
                        float maxDrive = 1.0f);

    /** @brief Read STATUS (clears the latched flags). */
    Status readStatus();
    /** @brief DEVICE_ID (3 for the DRV2605); clears the latched flags. */
    uint8_t readDeviceId();
    /** @brief VDD in V from VBAT; valid only during playback. */
    float readSupplyVoltage();
    /** @brief LRA resonance frequency in Hz; valid only during LRA playback; 0 if unavailable. */
    float readLraFrequency();
    /** @brief Burn registers 0x16–0x1A into OTP. IRREVERSIBLE, one time only; VDD must be 4.0–4.4 V.
     *  @param confirm Must be true. @return true if programmed; false if not confirmed, already programmed, or it timed out. */
    bool programOtp(bool confirm = false);
    /** @brief OTP_STATUS: true if the OTP has been programmed. */
    bool otpProgrammed();

protected:
    bool _isLra();
    bool _isOpenLoop(bool lra);
    bool _voltsToRatedCode(float volts, float lraFreqHz, uint8_t& code);
    static uint8_t _driveTimeCode(float ms, bool lra);
};
