#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"
#include "../../connection/InputPin.h"
#include "../../connection/OutputPin.h"

/** @brief Trinamic TMC2130 stepper driver over SPI (Mode 3) — minimal interface.
 *
 *  Brings the driver up in SPI mode (internal reference, StealthChop below
 *  TPWMTHRS = 500, SpreadCycle above it, 16 microsteps with 256-step
 *  interpolation, 0.5 A RMS run current) and moves the motor with STEP/DIR
 *  pulses -- the chip has no internal step generator. The 40-bit datagram
 *  framing and the pipelined two-datagram read are handled here; the
 *  connection only needs plain write / write_read (SPI Mode 3, MSB first).
 *
 *  The constructor talks to the chip (identity check, configuration with
 *  mirror-verified writes). Check ok() afterwards: it is false when the chip
 *  did not answer, reported an unexpected identity, or a write was not
 *  mirrored back.
 */
class TMC2130Minimal {
public:
    static constexpr uint8_t FAULT_OVERTEMP_WARNING = 0x01; ///< DRV_STATUS.otpw
    static constexpr uint8_t FAULT_OVERTEMP         = 0x02; ///< DRV_STATUS.ot
    static constexpr uint8_t FAULT_SHORT_GND        = 0x04; ///< s2ga / s2gb
    static constexpr uint8_t FAULT_OPEN_LOAD        = 0x10; ///< ola / olb

    /** @brief Construct and initialise the driver.
     *  @param connection SPI connection configured for Mode 3 (CPOL=1, CPHA=1).
     *  @param stepPin    STEP output (required for motion).
     *  @param dirPin     DIR output (required for motion).
     *  @param rSenseOhm  External sense resistor in ohm (0.11 on most boards).
     *  @param enPin      Optional ENN output (active low); driven high (off) until enable().
     */
    TMC2130Minimal(Connection& connection, OutputPin* stepPin, OutputPin* dirPin,
                   float rSenseOhm = 0.11f, OutputPin* enPin = nullptr);

    /** @brief Switch the power stage on (ENN low, or TOFF restored without a pin) and wait 100 ms. */
    void enable();

    /** @brief Switch the power stage off; the motor freewheels. */
    void disable();

    /** @brief Set the run current; hold current becomes one third of it.
     *  CHOPCONF.vsense is selected automatically unless setVsense() was called.
     *  @param rmsA Run current in A RMS (at most 1.4), quantised to 32 scale steps.
     *  @return false (and no write) if rmsA > 1.4 or the driver uses the internal sense resistors.
     */
    bool setCurrent(float rmsA);

    /** @brief Move a number of full steps (blocking, software-timed STEP pulses).
     *  @param fullSteps      Full steps; the sign sets the direction.
     *  @param fullStepsPerS  Speed in full steps per second (> 0).
     *  @return false if fullStepsPerS <= 0 or a pin is missing.
     */
    bool move(int32_t fullSteps, float fullStepsPerS);

    /** @brief Standstill flag.
     *  @return true when DRV_STATUS.stst is set.
     */
    bool isStandstill();

    /** @brief Driver fault flags.
     *  @return Bitmask of FAULT_* constants, 0 when none.
     */
    uint8_t readFault();

    /** @brief Whether the last transaction (or the constructor's init) succeeded. */
    bool ok() const { return _ok; }

protected:
    enum : uint8_t {
        REG_GCONF = 0x00, REG_GSTAT = 0x01, REG_IOIN = 0x04, REG_IHOLD_IRUN = 0x10,
        REG_TPOWERDOWN = 0x11, REG_TSTEP = 0x12, REG_TPWMTHRS = 0x13, REG_TCOOLTHRS = 0x14,
        REG_THIGH = 0x15, REG_XDIRECT = 0x2D, REG_VDCMIN = 0x33, REG_MSLUT0 = 0x60,
        REG_MSLUTSEL = 0x68, REG_MSLUTSTART = 0x69, REG_MSCNT = 0x6A, REG_MSCURACT = 0x6B,
        REG_CHOPCONF = 0x6C, REG_COOLCONF = 0x6D, REG_DCCTRL = 0x6E, REG_DRV_STATUS = 0x6F,
        REG_PWMCONF = 0x70, REG_PWM_SCALE = 0x71, REG_LOST_STEPS = 0x73
    };

    bool writeReg(uint8_t reg, uint32_t value, bool verify = false);
    uint32_t readReg(uint8_t reg);
    bool modify(uint8_t reg, uint32_t mask, uint32_t value);
    uint32_t shadow(uint8_t reg) const;
    bool currentToCs(float rmsA, int8_t forcedVsense, uint8_t& cs, bool& vsense) const;
    bool writeIholdIrun(bool verify = false);
    void delayMs(unsigned ms);
    void delayUs(unsigned us);

    Connection& _conn;
    OutputPin* _stepPin;
    OutputPin* _dirPin;
    OutputPin* _enPin;
    float _rSense;
    bool _ok;
    bool _internalRsense;
    bool _vsense;
    bool _autoVsense;
    uint16_t _microsteps;
    uint8_t _toff;
    uint8_t _runCs, _holdCs, _iholdDelay;
    uint32_t _gconf, _chopconf, _pwmconf, _coolconf;

private:
    uint32_t secondDatagram();
    void initialise();
};

/** @brief Complete TMC2130 driver. Extends TMC2130Minimal.
 *
 *  Adds raw register access, independent hold current, microstep, chopper
 *  and StealthChop tuning, StallGuard2 / CoolStep, DIAG routing, DcStep,
 *  direct coil-current mode, the microstep table and every status read-back.
 *  Setters that validate an argument return false (and write nothing) when
 *  it is out of range.
 */
class TMC2130Full : public TMC2130Minimal {
public:
    /** @brief Decoded DRV_STATUS. */
    struct Status {
        bool stst, fsactive; uint8_t csActual; uint16_t sgResult;
        bool stallguard, otpw, ot, shortGnd, openLoad;
    };
    /** @brief Decoded GSTAT. */
    struct GlobalStatus { bool reset, drvErr, uvCp; };
    /** @brief Decoded IOIN. */
    struct Ioin { uint32_t raw; bool step, dir, dcen, dcin, enn, dco; uint8_t version; };
    /** @brief The two signed 9-bit MSCURACT values. */
    struct CoilCurrents { int16_t curA, curB; };

    /** @brief Construct and initialise the driver.
     *  @param connection SPI connection configured for Mode 3.
     *  @param stepPin    STEP output.
     *  @param dirPin     DIR output.
     *  @param rSenseOhm  External sense resistor in ohm.
     *  @param enPin      Optional ENN output (active low).
     *  @param diag0Pin   Optional DIAG0 input.
     *  @param diag1Pin   Optional DIAG1 input.
     */
    TMC2130Full(Connection& connection, OutputPin* stepPin, OutputPin* dirPin,
                float rSenseOhm = 0.11f, OutputPin* enPin = nullptr,
                InputPin* diag0Pin = nullptr, InputPin* diag1Pin = nullptr);

    /** @brief Raw 32-bit pipelined register read; sets ok() false for write-only registers. */
    uint32_t readRegister(uint8_t reg);
    /** @brief Raw 32-bit register write; @p verify adds the mirror check.
     *  @return false for read-only / reserved registers or a failed mirror check. */
    bool writeRegister(uint8_t reg, uint32_t value, bool verify = false);
    /** @brief Independent hold current in A RMS. */
    bool setHoldCurrent(float rmsA);
    /** @brief IHOLDDELAY 0-15 and TPOWERDOWN 0-255; false if out of range. */
    bool setCurrentDelay(uint8_t iholdDelay, uint8_t tpowerdown);
    /** @brief Microstep resolution 1,2,4,...,256; false for any other value. */
    bool setMicrosteps(uint16_t n);
    /** @brief CHOPCONF.intpol. */
    void setInterpolation(bool enabled);
    /** @brief SpreadCycle only (true) or StealthChop (false); switch at standstill. */
    void setChopperMode(bool spreadcycle);
    /** @brief TPWMTHRS (20 bit); 0 disables the threshold. */
    bool setStealthchopThreshold(uint32_t tpwmthrs);
    /** @brief THIGH (20 bit). */
    bool setHighVelocityThreshold(uint32_t thigh);
    /** @brief SpreadCycle (chm = 0) fields: toff 0-15, tbl 0-3, hstrt 0-7, raw hend 0-15. */
    bool configureChopper(uint8_t toff, uint8_t tbl, uint8_t hstrt, uint8_t hend);
    /** @brief Constant-off-time (chm = 1) fields: tfd 0-15, offset -3..12. */
    bool configureConstantOffTime(uint8_t toff, uint8_t tbl, uint8_t tfd, int8_t offset, bool disfdcc, bool rndtf);
    /** @brief CHOPCONF.vhighchm / vhighfs. */
    void configureHighVelocity(bool vhighchm, bool vhighfs);
    /** @brief CHOPCONF.SYNC 0-15 (chopSync2); false if out of range. */
    bool setChopperSync(uint8_t sync);
    /** @brief PWMCONF StealthChop fields (freewheel is kept); pwmFreq 0-3. */
    bool configureStealthchop(bool autoscale, bool symmetric, uint8_t pwmAmpl, uint8_t pwmGrad, uint8_t pwmFreq);
    /** @brief PWMCONF.freewheel 0-3; false otherwise. */
    bool setFreewheel(uint8_t mode);
    /** @brief CHOPCONF.vsense; disables the automatic selection in setCurrent(). */
    void setVsense(bool highSensitivity);
    /** @brief GCONF.internal_Rsense (setCurrent() then fails). */
    void setInternalRsense(bool enabled);
    /** @brief GCONF.I_scale_analog. */
    void setAnalogScaling(bool enabled);
    /** @brief GCONF.shaft. */
    void setDirectionInverted(bool inverted);
    /** @brief Route events to DIAG0 / DIAG1 (GCONF bits 5-13). */
    void configureDiag(bool diag0Error, bool diag0Otpw, bool diag0Stall, bool diag1Stall,
                       bool diag1Index, bool diag1Onstate, bool diag0Pushpull, bool diag1Pushpull);
    /** @brief CHOPCONF.dedge. */
    void setDoubleEdge(bool enabled);
    /** @brief true keeps the short-to-GND protection (CHOPCONF.diss2g = 0). */
    void setShortProtection(bool shortGnd);
    /** @brief GCONF.small_hysteresis. */
    void setSmallHysteresis(bool enabled);
    /** @brief GCONF.stop_enable. */
    void setEmergencyStop(bool enabled);
    /** @brief COOLCONF.sgt -64..63 (lower = more sensitive); other fields are kept; false if out of range. */
    bool setStallThreshold(int8_t sgt);
    /** @brief COOLCONF.sfilt. */
    void setStallguardFilter(bool enabled);
    /** @brief TCOOLTHRS (20 bit); 0 disables; false if out of range. */
    bool setCoolstepThreshold(uint32_t tcoolthrs);
    /** @brief COOLCONF CoolStep fields (sgt / sfilt kept): semin 0-15 (0 disables), semax 0-15,
     *         seup 0-3, sedn 0-3, seimin 0|1; false if out of range. */
    bool configureCoolstep(uint8_t semin, uint8_t semax, uint8_t seup, uint8_t sedn, uint8_t seimin);
    /** @brief SG_RESULT 0-1023 (0 = highest load). */
    uint16_t readStallguard();
    /** @brief DRV_STATUS.StallGuard. */
    bool isStalled();
    /** @brief Level of DIAG0 (n = 0) or DIAG1 (n = 1); false and ok() false if not wired. */
    bool readDiag(uint8_t n);
    /** @brief Pulse the STEP pin @p count times (negative = reverse via DIR); no microstep conversion.
     *  @param count   Pulses; sign gives direction.
     *  @param delayS  Time between edges in seconds.
     *  @return false if a pin is missing. */
    bool step(int32_t count, float delayS);
    /** @brief GCONF.direct_mode. */
    void setDirectMode(bool enabled);
    /** @brief XDIRECT coil currents -255..255; false if direct mode is off or out of range. */
    bool setCoilCurrents(int16_t coilA, int16_t coilB);
    /** @brief VDCMIN (0..2^23-1), DCCTRL.DC_TIME (0-1023), DC_SG (0-255); false if out of range. */
    bool setDcstep(uint32_t vdcmin, uint16_t dcTime, uint8_t dcSg);
    /** @brief Program MSLUT[0..7], MSLUTSEL and MSLUTSTART; call at MSCNT = 0. */
    void writeMicrostepTable(const uint32_t mslut[8], uint32_t mslutsel, uint32_t mslutstart);
    /** @brief MSCNT 0-1023. */
    uint16_t microstepPosition();
    /** @brief DRV_STATUS.CS_ACTUAL 0-31. */
    uint8_t readCurrentScale();
    /** @brief Signed (CUR_A, CUR_B) from MSCURACT. */
    CoilCurrents readCoilCurrents();
    /** @brief TSTEP (20 bit). */
    uint32_t readTstep();
    /** @brief PWM_SCALE 0-255. */
    uint8_t readPwmScale();
    /** @brief LOST_STEPS (20 bit, wraps). */
    uint32_t readLostSteps();
    /** @brief Decoded DRV_STATUS (one read). */
    Status readStatus();
    /** @brief Decoded GSTAT; the chip clears the flags on read. */
    GlobalStatus readGlobalStatus();
    /** @brief Decoded IOIN. */
    Ioin readIoin();

private:
    InputPin* _diag0;
    InputPin* _diag1;
};
