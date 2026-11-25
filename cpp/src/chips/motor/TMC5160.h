#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"
#include "../../connection/InputPin.h"
#include "../../connection/OutputPin.h"

/** @brief Trinamic TMC5160 stepper controller/driver over SPI (Mode 3) or single-wire UART -- minimal interface.
 *
 *  Brings the chip up with the internal SixPoint ramp generator in positioning
 *  mode (256 microsteps, StealthChop below TPWMTHRS = 500, 0.5 A RMS run
 *  current, 200 full steps/s, 400 full steps/s^2) and moves the motor with
 *  register writes -- no STEP pulses. The transport follows the constructor:
 *  the SPI constructor builds pipelined 40-bit datagrams, the UART constructor
 *  builds CRC-protected datagrams. Positions are in full steps, velocities in
 *  full steps per second, accelerations in full steps per second squared.
 *
 *  The constructor talks to the chip (identity check, configuration). Check
 *  ok() afterwards: it is false when the chip did not answer, reported an
 *  unexpected identity (IOIN.VERSION != 0x30), is strapped for STEP/DIR, or a
 *  write was not acknowledged.
 */
class TMC5160Minimal {
public:
    static constexpr uint8_t FAULT_OVERTEMP_WARNING = 0x01; ///< DRV_STATUS.otpw
    static constexpr uint8_t FAULT_OVERTEMP         = 0x02; ///< DRV_STATUS.ot
    static constexpr uint8_t FAULT_SHORT_GND        = 0x04; ///< s2ga / s2gb
    static constexpr uint8_t FAULT_SHORT_SUPPLY     = 0x08; ///< s2vsa / s2vsb
    static constexpr uint8_t FAULT_OPEN_LOAD        = 0x10; ///< ola / olb (informative only)

    /** @brief Construct on SPI and initialise the driver.
     *  @param connection SPI connection configured for Mode 3 (CPOL=1, CPHA=1).
     *  @param rSenseOhm  External sense resistor in ohm (0.075 on common boards).
     *  @param enPin      Optional DRV_ENN output (active low); driven high (off) until enable().
     */
    TMC5160Minimal(Connection& connection, float rSenseOhm = 0.075f, OutputPin* enPin = nullptr);

    /** @brief Construct on the single-wire UART and initialise the driver.
     *  @param connection UART connection.
     *  @param rSenseOhm  External sense resistor in ohm.
     *  @param address    Node address 0-253 (ok() is false otherwise).
     *  @param enPin      Optional DRV_ENN output (active low).
     *  @param echo       True when TX and RX share the wire.
     *  @param multiNode  Write SLAVECONF.SENDDELAY = 2 first (several chips on one UART).
     */
    TMC5160Minimal(Connection& connection, float rSenseOhm, uint8_t address, OutputPin* enPin, bool echo,
                   bool multiNode = false);

    /** @brief Switch the power stage on (DRV_ENN low, or TOFF restored without a pin) and wait 150 ms. */
    void enable();

    /** @brief Switch the power stage off; the motor outputs float. */
    void disable();

    /** @brief Set the run current; hold current becomes one third of it.
     *  @param rmsA Run current in A RMS, between 1/64 and 1x of the sense-resistor maximum.
     *  @return false (and no write) if out of range.
     */
    bool setCurrent(float rmsA);

    /** @brief Set VMAX in full steps per second (applied immediately in positioning mode). @return false if <= 0 or out of range. */
    bool setSpeed(float fullStepsPerS);

    /** @brief Set a single trapezoid: A1 = AMAX = DMAX = D1 (with V1 = 0), full steps per second squared. @return false if out of range. */
    bool setAcceleration(float fullStepsPerS2);

    /** @brief Start a positioning move to an absolute position in full steps (non-blocking). */
    bool moveTo(float position);

    /** @brief Blocking relative move; returns when the target is reached.
     *  @param fullSteps      Full steps; the sign sets the direction.
     *  @param fullStepsPerS  Optional speed override (> 0), restored afterwards; 0 keeps the configured speed.
     */
    bool moveSteps(float fullSteps, float fullStepsPerS = 0.0f);

    /** @brief Velocity mode: ramp to a velocity (sign = direction); 0 ramps to standstill. */
    bool run(float fullStepsPerS);

    /** @brief Controlled stop: velocity mode with VMAX = 0. */
    bool stop();

    /** @brief XACTUAL in full steps. */
    float position();

    /** @brief Define the current position without motion (writes XACTUAL and XTARGET). */
    bool setPosition(float position);

    /** @brief VACTUAL in full steps per second, signed. */
    float velocity();

    /** @brief RAMP_STAT.position_reached. */
    bool isPositionReached();

    /** @brief Poll RAMP_STAT every 5 ms until position_reached (positioning) or vzero (velocity mode).
     *  @param timeoutS Timeout in seconds; <= 0 estimates it from the last move.
     *  @return true when reached, false on timeout or bus error.
     */
    bool waitUntilReached(float timeoutS = 0.0f);

    /** @brief Standstill flag. @return true when DRV_STATUS.stst is set. */
    bool isStandstill();

    /** @brief Driver fault flags. @return Bitmask of FAULT_* constants, 0 when none. */
    uint8_t readFault();

    /** @brief CRC-8 (polynomial 0x07, init 0, LSB first) over a UART datagram body. */
    static uint8_t crc8(const uint8_t* data, size_t len);

    /** @brief Whether the last transaction (or the constructor's init) succeeded. */
    bool ok() const { return _ok; }

protected:
    enum : uint8_t {
        REG_GCONF = 0x00, REG_GSTAT = 0x01, REG_IFCNT = 0x02, REG_SLAVECONF = 0x03, REG_IOIN = 0x04,
        REG_OUTPUT = 0x04, REG_X_COMPARE = 0x05, REG_SHORT_CONF = 0x09, REG_DRV_CONF = 0x0A,
        REG_GLOBALSCALER = 0x0B, REG_OFFSET_READ = 0x0C, REG_IHOLD_IRUN = 0x10, REG_TPOWERDOWN = 0x11,
        REG_TSTEP = 0x12, REG_TPWMTHRS = 0x13, REG_TCOOLTHRS = 0x14, REG_THIGH = 0x15, REG_RAMPMODE = 0x20,
        REG_XACTUAL = 0x21, REG_VACTUAL = 0x22, REG_VSTART = 0x23, REG_A1 = 0x24, REG_V1 = 0x25,
        REG_AMAX = 0x26, REG_VMAX = 0x27, REG_DMAX = 0x28, REG_D1 = 0x2A, REG_VSTOP = 0x2B,
        REG_TZEROWAIT = 0x2C, REG_XTARGET = 0x2D, REG_VDCMIN = 0x33, REG_SW_MODE = 0x34,
        REG_RAMP_STAT = 0x35, REG_XLATCH = 0x36, REG_ENCMODE = 0x38, REG_X_ENC = 0x39,
        REG_ENC_CONST = 0x3A, REG_ENC_STATUS = 0x3B, REG_ENC_LATCH = 0x3C, REG_ENC_DEVIATION = 0x3D,
        REG_MSLUT0 = 0x60, REG_MSLUTSEL = 0x68, REG_MSLUTSTART = 0x69, REG_MSCNT = 0x6A,
        REG_MSCURACT = 0x6B, REG_CHOPCONF = 0x6C, REG_COOLCONF = 0x6D, REG_DCCTRL = 0x6E,
        REG_DRV_STATUS = 0x6F, REG_PWMCONF = 0x70, REG_PWM_SCALE = 0x71, REG_PWM_AUTO = 0x72
    };

    bool writeReg(uint8_t reg, uint32_t value);
    bool writeVerified(uint8_t reg, uint32_t value);
    uint32_t readReg(uint8_t reg);
    bool modify(uint8_t reg, uint32_t mask, uint32_t value);
    void firstUse(uint8_t reg, uint32_t reset);
    int64_t vReg(double v) const;
    int64_t aReg(double a) const;
    double iMax() const;
    bool applyCurrent(float rmsA);
    bool writeIholdIrun();
    void delayMs(unsigned ms);

    Connection& _conn;
    OutputPin* _enPin;
    float _rSense;
    bool _uart;
    bool _echo;
    bool _multiNode;
    uint8_t _address;
    bool _ok;
    uint32_t _fclk;
    uint32_t _usc;
    uint8_t _toff;
    uint8_t _rampMode;
    float _speed, _accel;
    uint32_t _speedReg;
    uint8_t _irun, _ihold, _iholdDelay, _sendDelay;
    float _lastDist, _lastSpeed, _lastAccel;
    uint8_t _writes;
    uint32_t _shadow[128];
    uint8_t _seen[16];

private:
    bool writeUart(uint8_t reg, uint32_t value);
    bool readUart(uint8_t reg, uint32_t& value);
    void recover();
    uint32_t spiSecond();
    void initialise();
};

/** @brief Complete TMC5160 driver. Extends TMC5160Minimal.
 *
 *  Adds raw register access, the full SixPoint ramp, reference switches,
 *  chopper and StealthChop tuning, StallGuard2 / CoolStep, DcStep, the ABN
 *  encoder, direct coil-current mode, the microstep table and every status
 *  read-back. Setters that validate an argument return false (and write
 *  nothing) when it is out of range; UART-only setters also return false on SPI.
 */
class TMC5160Full : public TMC5160Minimal {
public:
    /** @brief Decoded DRV_STATUS. */
    struct Status {
        bool stst, stealth, fsactive; uint8_t csActual; uint16_t sgResult;
        bool stallguard, otpw, ot, shortGnd, shortSupply, openLoad;
    };
    /** @brief Decoded RAMP_STAT. */
    struct RampStatus {
        bool statusStopL, statusStopR, statusLatchL, statusLatchR, eventStopL, eventStopR, eventStopSg,
             eventPosReached, velocityReached, positionReached, vzero, tZerowaitActive, secondMove, statusSg;
    };
    /** @brief Decoded GSTAT. */
    struct GlobalStatus { bool reset, drvErr, uvCp; };
    /** @brief Decoded IOIN. */
    struct Ioin { uint32_t raw; bool refl, refr, encb, enca, drvEnn, encn, sdMode, swcompIn; uint8_t version; };
    /** @brief The two signed 9-bit MSCURACT values. */
    struct CoilCurrents { int16_t curA, curB; };

    /** @brief Construct on SPI (Mode 3); diag0Pin / diag1Pin are optional DIAG0 / DIAG1 inputs. */
    TMC5160Full(Connection& connection, float rSenseOhm = 0.075f, OutputPin* enPin = nullptr,
                InputPin* diag0Pin = nullptr, InputPin* diag1Pin = nullptr);
    /** @brief Construct on the single-wire UART (the DIAG pins carry the UART and are unavailable). */
    TMC5160Full(Connection& connection, float rSenseOhm, uint8_t address, OutputPin* enPin, bool echo,
                bool multiNode = false);

    /** @brief Raw 32-bit read of a readable register; ok() false for write-only registers. */
    uint32_t readRegister(uint8_t reg);
    /** @brief Raw 32-bit write; @p verify adds the SPI mirror / UART IFCNT check. @return false for read-only registers or a failed check. */
    bool writeRegister(uint8_t reg, uint32_t value, bool verify = false);
    /** @brief Independent hold current in A RMS (not above the run current). */
    bool setHoldCurrent(float rmsA);
    /** @brief Raw GLOBALSCALER: 0 or 32-256 (256 is written as 0). */
    bool setGlobalScaler(uint16_t value);
    /** @brief Raw IHOLD_IRUN fields: irun 0-31, ihold 0-31, iholdDelay 0-15. */
    bool setCurrentScale(uint8_t irun, uint8_t ihold, uint8_t iholdDelay);
    /** @brief TPOWERDOWN 0-255 (values below 2 break StealthChop auto-tuning). */
    bool setPowerDownDelay(uint8_t tpowerdown);
    /** @brief Program the full SixPoint ramp in physical units (full steps/s and full steps/s^2). */
    bool configureRamp(float vstart, float a1, float v1, float amax, float vmax, float dmax, float d1, float vstop);
    /** @brief Program the full SixPoint ramp in raw register units. */
    bool setRampRaw(uint32_t vstart, uint32_t a1, uint32_t v1, uint32_t amax, uint32_t vmax, uint32_t dmax,
                    uint32_t d1, uint32_t vstop);
    /** @brief TZEROWAIT in seconds (0 to about 2.8 s at 12 MHz). */
    bool setZeroWait(float seconds);
    /** @brief RAMPMODE 0-3 (3 = hold). */
    bool setRampMode(uint8_t mode);
    /** @brief Hold mode: keep the present velocity until a stop event. */
    bool hold();
    /** @brief Write XTARGET in microsteps (starts positioning in mode 0). */
    bool setTargetRaw(int32_t xtarget);
    /** @brief Decoded RAMP_STAT (one read). */
    RampStatus readRampStatus();
    /** @brief Write 1 to the clear-on-write bits of RAMP_STAT. */
    bool clearRampEvents();
    /** @brief X_COMPARE in full steps (DIAG1 goes high while XACTUAL equals it). */
    bool setPositionCompare(float position);
    /** @brief SW_MODE bits 0-4 and 11 (latch and sg_stop bits are kept); false if soft stop conflicts with sg_stop. */
    bool configureSwitches(bool stopLeft, bool stopRight, bool leftActiveLow, bool rightActiveLow, bool swap, bool softStop);
    /** @brief SW_MODE latch bits 5-9. */
    bool configureLatch(bool leftActive, bool leftInactive, bool rightActive, bool rightInactive, bool latchEncoder);
    /** @brief XLATCH in full steps. */
    float readLatchedPosition();
    /** @brief status_stop_l / status_stop_r from RAMP_STAT. */
    void readSwitches(bool& left, bool& right);
    /** @brief Microstep resolution 1,2,4,...,256 (the ramp registers are not rewritten); false otherwise. */
    bool setMicrosteps(uint16_t n);
    /** @brief CHOPCONF.intpol. */
    bool setInterpolation(bool enabled);
    /** @brief SpreadCycle only (true) or StealthChop (false); switch at standstill. */
    bool setChopperMode(bool spreadcycle);
    /** @brief TPWMTHRS (20 bit); 0 disables the velocity switch. */
    bool setStealthchopThreshold(uint32_t tpwmthrs);
    /** @brief THIGH (20 bit). */
    bool setHighVelocityThreshold(uint32_t thigh);
    /** @brief TCOOLTHRS (20 bit); 0 disables. */
    bool setCoolstepThreshold(uint32_t tcoolthrs);
    /** @brief SpreadCycle (chm = 0) fields: toff 0-15, tbl 0-3, hstrt 0-7, raw hend 0-15, tpfd 0-15. */
    bool configureChopper(uint8_t toff, uint8_t tbl, uint8_t hstrt, uint8_t hend, uint8_t tpfd);
    /** @brief Constant-off-time (chm = 1) fields: tfd 0-15, offset -3..12. */
    bool configureConstantOffTime(uint8_t toff, uint8_t tbl, uint8_t tfd, int8_t offset, bool disfdcc, uint8_t tpfd);
    /** @brief CHOPCONF.vhighchm / vhighfs. */
    bool configureHighVelocity(bool vhighchm, bool vhighfs);
    /** @brief PWMCONF StealthChop fields (freewheel is kept): pwmFreq 0-3, pwmReg 1-15, pwmLim 0-15. */
    bool configureStealthchop(bool autoscale, bool autograd, uint8_t pwmOfs, uint8_t pwmGrad, uint8_t pwmFreq,
                              uint8_t pwmReg, uint8_t pwmLim);
    /** @brief PWMCONF.freewheel 0-3. */
    bool setFreewheel(uint8_t mode);
    /** @brief true keeps the protections (CHOPCONF.diss2g / diss2vs = 0). */
    bool setShortProtection(bool shortGnd, bool shortSupply);
    /** @brief CHOPCONF.dedge (external STEP/DIR only). */
    bool setDoubleEdge(bool enabled);
    /** @brief GCONF.shaft. */
    bool setDirectionInverted(bool inverted);
    /** @brief GCONF.faststandstill. */
    bool setFastStandstill(bool enabled);
    /** @brief GCONF.recalibrate. */
    bool setRecalibrate(bool enabled);
    /** @brief GCONF.small_hysteresis. */
    bool setSmallHysteresis(bool enabled);
    /** @brief GCONF.stop_enable. */
    bool setEmergencyStop(bool enabled);
    /** @brief GCONF bits 7, 8, 12, 13 (SD_MODE = 0 meanings). */
    bool configureDiag(bool diag0StepOutput, bool diag1DirOutput, bool diag0Pushpull, bool diag1Pushpull);
    /** @brief COOLCONF.sgt -64..63 (lower = more sensitive); other fields are kept. */
    bool setStallThreshold(int8_t sgt);
    /** @brief COOLCONF.sfilt. */
    bool setStallguardFilter(bool enabled);
    /** @brief COOLCONF CoolStep fields (sgt / sfilt kept): semin 0-15, semax 0-15, seup 0-3, sedn 0-3, seimin 0|1. */
    bool configureCoolstep(uint8_t semin, uint8_t semax, uint8_t seup, uint8_t sedn, uint8_t seimin);
    /** @brief SW_MODE.sg_stop: stop the motor on a StallGuard2 event (needs TCOOLTHRS; not with soft stop). */
    bool enableStallStop(bool enabled);
    /** @brief SG_RESULT 0-1023 (0 = highest load). */
    uint16_t readStallguard();
    /** @brief DRV_STATUS.StallGuard. */
    bool isStalled();
    /** @brief Level of DIAG0 (n = 0) or DIAG1 (n = 1); false and ok() false if not wired. */
    bool readDiag(uint8_t n);
    /** @brief VDCMIN (0..2^23-1), DCCTRL.DC_TIME (0-1023), DC_SG (0-255); false if out of range. */
    bool setDcstep(uint32_t vdcmin, uint16_t dcTime, uint8_t dcSg);
    /** @brief GCONF.direct_mode (the ramp generator is bypassed while set; leaving it rewrites XTARGET). */
    bool setDirectMode(bool enabled);
    /** @brief XDIRECT coil currents -255..255; false if direct mode is off or out of range. */
    bool setCoilCurrents(int16_t coilA, int16_t coilB);
    /** @brief Program MSLUT[0..7], MSLUTSEL and MSLUTSTART; call at MSCNT = 0. */
    bool writeMicrostepTable(const uint32_t mslut[8], uint32_t mslutsel, uint32_t mslutstart);
    /** @brief DRV_CONF -- board-specific, wrong values can destroy the MOSFETs. */
    bool configureGateDriver(uint8_t bbmtime, uint8_t bbmclks, uint8_t otselect, uint8_t drvstrength, uint8_t filtIsense);
    /** @brief SHORT_CONF -- board-specific: s2vsLevel 4-15, s2gLevel 2-15, shortfilter 0-3, shortdelay 0|1. */
    bool configureShortDetection(uint8_t s2vsLevel, uint8_t s2gLevel, uint8_t shortfilter, uint8_t shortdelay);
    /** @brief ENCMODE; flags in register bit order: pol_a, pol_b, pol_n, ignore_ab, clr_cont, clr_once, pos_edge,
     *         neg_edge, clr_enc_x, latch_x_act, decimal (11 entries). */
    bool configureEncoder(const bool flags[11]);
    /** @brief ENC_CONST: signed 16.16 (or integer + 4-digit decimal fraction when ENCMODE.enc_sel_decimal is set). */
    bool setEncoderConstant(double value);
    /** @brief X_ENC. */
    bool setEncoderPosition(int32_t counts);
    /** @brief X_ENC signed. */
    int32_t readEncoderPosition();
    /** @brief ENC_LATCH signed. */
    int32_t readEncoderLatch();
    /** @brief ENC_DEVIATION 0..2^20-1 (0 = off). */
    bool setEncoderDeviation(uint32_t steps);
    /** @brief ENC_STATUS n_event / deviation_warn. */
    void readEncoderStatus(bool& nEvent, bool& deviationWarn);
    /** @brief Write 1 to the ENC_STATUS event bits. */
    bool clearEncoderStatus();
    /** @brief SLAVECONF.SLAVEADDR 0-253 (UART only); later datagrams use the new address. */
    bool setSlaveAddress(uint8_t slaveaddr);
    /** @brief SLAVECONF.SENDDELAY 0-15 (UART only; at least 2 on multi-node buses). */
    bool setSendDelay(uint8_t sendDelay);
    /** @brief OUTPUT bit 0, the NAO polarity (UART only). */
    bool setNaoPolarity(bool level);
    /** @brief IFCNT (UART only); ok() false on SPI. */
    uint8_t readIfcnt();
    /** @brief MSCNT 0-1023. */
    uint16_t microstepPosition();
    /** @brief DRV_STATUS.CS_ACTUAL 0-31. */
    uint8_t readCurrentScale();
    /** @brief Signed (CUR_A, CUR_B) from MSCURACT. */
    CoilCurrents readCoilCurrents();
    /** @brief TSTEP (20 bit). */
    uint32_t readTstep();
    /** @brief PWM_SCALE_SUM (0-255) and PWM_SCALE_AUTO (signed 9 bit). */
    void readPwmScale(uint8_t& sum, int16_t& autoScale);
    /** @brief PWM_OFS_AUTO and PWM_GRAD_AUTO. */
    void readPwmAuto(uint8_t& ofs, uint8_t& grad);
    /** @brief Signed phase A / phase B offsets from OFFSET_READ. */
    void readOffset(int8_t& phaseA, int8_t& phaseB);
    /** @brief Decoded DRV_STATUS (one read). */
    Status readStatus();
    /** @brief Decoded GSTAT (does not clear). */
    GlobalStatus readGlobalStatus();
    /** @brief Write 0x07 to GSTAT. */
    bool clearGlobalStatus();
    /** @brief Decoded IOIN. */
    Ioin readIoin();

private:
    bool gconfBit(uint32_t mask, bool on);
    bool threshold(uint8_t reg, uint32_t v);
    InputPin* _diag0;
    InputPin* _diag1;
};
