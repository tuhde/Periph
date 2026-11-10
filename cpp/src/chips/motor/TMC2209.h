#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"
#include "../../connection/InputPin.h"
#include "../../connection/OutputPin.h"

/** @brief Trinamic TMC2209 stepper driver over single-wire UART — minimal interface.
 *
 *  Brings the driver up in UART mode (pdn_disable, microstep resolution from
 *  CHOPCONF.MRES), StealthChop, 16 microsteps with 256-step interpolation and
 *  0.5 A RMS run current, then lets the internal step generator (VACTUAL)
 *  spin the motor -- no STEP pulses required. Datagram framing (sync byte,
 *  CRC8, host echo) is handled here; the connection only needs plain
 *  write / read / write_read.
 *
 *  The constructor talks to the chip (identity check, configuration, IFCNT
 *  acknowledgement). Check ok() afterwards: it is false when the chip did not
 *  answer, reported an unexpected identity, or did not acknowledge the writes, or the node address was not 0-3.
 */
class TMC2209Minimal {
public:
    static constexpr uint8_t FAULT_OVERTEMP_WARNING = 0x01; ///< DRV_STATUS.otpw
    static constexpr uint8_t FAULT_OVERTEMP         = 0x02; ///< DRV_STATUS.ot
    static constexpr uint8_t FAULT_SHORT_GND        = 0x04; ///< s2ga / s2gb
    static constexpr uint8_t FAULT_SHORT_SUPPLY     = 0x08; ///< s2vsa / s2vsb
    static constexpr uint8_t FAULT_OPEN_LOAD        = 0x10; ///< ola / olb

    /** @brief Construct and initialise the driver.
     *  @param connection UART connection to the chip's PDN_UART pin.
     *  @param rSenseOhm  External sense resistor in ohm (0.11 on most boards).
     *  @param address    UART node address 0-3, set by the MS1_AD0 / MS2_AD1 pins.
     *  @param enPin      Optional ENN output (active low); driven high (off) until enable().
     *  @param echo       true when TX and RX share PDN_UART so every byte is echoed.
     */
    explicit TMC2209Minimal(Connection& connection, float rSenseOhm = 0.11f, uint8_t address = 0,
                            OutputPin* enPin = nullptr, bool echo = true);

    /** @brief Switch the power stage on (ENN low, or TOFF restored without a pin). */
    void enable();

    /** @brief Switch the power stage off; the motor freewheels. */
    void disable();

    /** @brief Set the run current; hold current becomes one third of it.
     *  @param rmsA Run current in A RMS (at most 1.4), quantised to 32 scale steps.
     *  @return false (and no write) if the driver uses the internal sense resistors.
     */
    bool setCurrent(float rmsA);

    /** @brief Move with the internal step generator (VACTUAL).
     *  @param stepsPerS Full steps per second at the configured microstepping;
     *                   sign = direction, 0 stops.
     */
    void run(float stepsPerS);

    /** @brief Stop the internal step generator (VACTUAL = 0). */
    void stop();

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
    TMC2209Minimal(Connection& connection, float rSenseOhm, uint8_t address, OutputPin* enPin, bool echo,
                   uint32_t fclkHz, bool multiNode);

    enum : uint8_t {
        REG_GCONF = 0x00, REG_GSTAT = 0x01, REG_IFCNT = 0x02, REG_NODECONF = 0x03,
        REG_OTP_READ = 0x05, REG_IOIN = 0x06, REG_FACTORY_CONF = 0x07,
        REG_IHOLD_IRUN = 0x10, REG_TPOWERDOWN = 0x11, REG_TSTEP = 0x12,
        REG_TPWMTHRS = 0x13, REG_TCOOLTHRS = 0x14, REG_VACTUAL = 0x22,
        REG_SGTHRS = 0x40, REG_SG_RESULT = 0x41, REG_COOLCONF = 0x42, REG_MSCNT = 0x6A, REG_MSCURACT = 0x6B,
        REG_CHOPCONF = 0x6C, REG_DRV_STATUS = 0x6F, REG_PWMCONF = 0x70,
        REG_PWM_SCALE = 0x71, REG_PWM_AUTO = 0x72
    };

    /** @brief CRC-8 (poly 0x07) over @p len bytes. */
    static uint8_t crc8(const uint8_t* data, size_t len);
    bool writeReg(uint8_t reg, uint32_t value);
    uint32_t readReg(uint8_t reg);
    bool modify(uint8_t reg, uint32_t mask, uint32_t value);
    uint32_t shadow(uint8_t reg) const;
    bool currentToCs(float rmsA, uint8_t& cs) const;
    void writeIholdIrun();

    Connection& _conn;
    float _rSense;
    uint8_t _address;
    OutputPin* _enPin;
    bool _echo;
    uint32_t _fclk;
    bool _multiNode;
    bool _ok;
    bool _internalRsense;
    bool _vsense;
    uint16_t _microsteps;
    uint8_t _toff;
    uint8_t _runCs, _holdCs, _iholdDelay;
    uint8_t _txCount;
    uint32_t _gconf, _chopconf, _pwmconf, _nodeconf;

private:
    bool writeDatagram(uint8_t reg, uint32_t value);
    bool readDatagram(uint8_t reg, uint32_t& value);
    void recover();
    void initialise();
};

/** @brief Complete TMC2209 driver. Extends TMC2209Minimal.
 *
 *  Adds raw register access, independent hold current, microstep and chopper
 *  configuration, StealthChop tuning, GPIO STEP/DIR pulsing and every status
 *  read-back. Setters that validate an argument return false (and write
 *  nothing) when it is out of range.
 */
class TMC2209Full : public TMC2209Minimal {
public:
    /** @brief Decoded DRV_STATUS. */
    struct Status {
        bool stst, stealth; uint8_t csActual;
        bool otpw, ot, shortGnd, shortSupply, openLoad, t120, t143, t150, t157;
    };
    /** @brief Decoded GSTAT. */
    struct GlobalStatus { bool reset, drvErr, uvCp; };
    /** @brief Decoded IOIN identity fields. */
    struct Ioin {
        uint32_t raw; bool enn, ms1, ms2, diag, pdnUart, step, spread, dir; uint8_t version;
    };
    /** @brief StealthChop auto-tuning results. */
    struct PwmAuto { uint8_t pwmScaleSum; int16_t pwmScaleAuto; uint8_t pwmOfsAuto, pwmGradAuto; };
    /** @brief The two signed 9-bit MSCURACT values in register order. */
    struct CoilCurrents { int16_t low, high; };

    /** @brief Construct and initialise the driver.
     *  @param connection UART connection to PDN_UART.
     *  @param rSenseOhm  External sense resistor in ohm.
     *  @param address    UART node address 0-3.
     *  @param enPin      Optional ENN output (active low).
     *  @param echo       true when TX and RX share PDN_UART.
     *  @param stepPin    Optional STEP output.
     *  @param dirPin     Optional DIR output.
     *  @param diagPin    Optional DIAG input (stall / driver error).
     *  @param multiNode  true when several chips share the UART; init then writes
     *                    NODECONF.SENDDELAY = 2 first.
     *  @param fclkHz     Chip clock in Hz (12 MHz internal).
     */
    explicit TMC2209Full(Connection& connection, float rSenseOhm = 0.11f, uint8_t address = 0,
                         OutputPin* enPin = nullptr, bool echo = true,
                         OutputPin* stepPin = nullptr, OutputPin* dirPin = nullptr,
                         InputPin* diagPin = nullptr, bool multiNode = false,
                         uint32_t fclkHz = 12000000UL);

    /** @brief Raw 32-bit register read; sets ok() false for write-only registers. */
    uint32_t readRegister(uint8_t reg);
    /** @brief Raw 32-bit register write.
     *  @return false for read-only / reserved registers (OTP_PROG and FACTORY_CONF are never written). */
    bool writeRegister(uint8_t reg, uint32_t value);
    /** @brief Independent hold current in A RMS. */
    bool setHoldCurrent(float rmsA);
    /** @brief IHOLDDELAY 0-15 and TPOWERDOWN 0-255; false if out of range. */
    bool setCurrentDelay(uint8_t iholdDelay, uint8_t tpowerdown);
    /** @brief Microstep resolution 1,2,4,...,256; false for any other value. */
    bool setMicrosteps(uint16_t n);
    /** @brief CHOPCONF.intpol. */
    void setInterpolation(bool enabled);
    /** @brief GCONF.en_SpreadCycle: true = SpreadCycle, false = StealthChop. */
    void setChopperMode(bool spreadcycle);
    /** @brief TPWMTHRS (20 bit); 0 disables the threshold. */
    void setStealthchopThreshold(uint32_t tpwmthrs);
    /** @brief CHOPCONF TOFF, TBL, HSTRT and raw HEND fields. */
    void configureChopper(uint8_t toff, uint8_t tbl, uint8_t hstrt, uint8_t hend);
    /** @brief PWMCONF StealthChop fields. */
    void configureStealthchop(bool autoscale, bool autograd, uint8_t pwmOfs, uint8_t pwmGrad,
                              uint8_t pwmFreq, uint8_t pwmReg, uint8_t pwmLim);
    /** @brief PWMCONF.freewheel 0-3; false otherwise. */
    bool setFreewheel(uint8_t mode);
    /** @brief CHOPCONF.vsense; also changes the current conversion. */
    void setVsense(bool highSensitivity);
    /** @brief GCONF.internal_Rsense (setCurrent() then fails). */
    void setInternalRsense(bool enabled);
    /** @brief GCONF.I_scale_analog. */
    void setAnalogScaling(bool enabled);
    /** @brief GCONF.shaft. */
    void setDirectionInverted(bool inverted);
    /** @brief INDEX output: 0 first microstep, 1 otpw, 2 step pulses; false otherwise. */
    bool setIndexOutput(uint8_t mode);
    /** @brief Keep (true) or disable (false) the low-side-short / short-to-GND protections. */
    void setDriverProtection(bool shortLs, bool shortGnd);
    /** @brief CHOPCONF.dedge. */
    void setDoubleEdge(bool enabled);
    /** @brief NODECONF.SENDDELAY 0-15; false if out of range. */
    bool setSendDelay(uint8_t n);
    /** @brief StallGuard4 threshold SGTHRS 0-255 (higher = more sensitive); a stall is
     *         signalled when SG_RESULT <= 2 * sgthrs. */
    void setStallThreshold(uint8_t sgthrs);
    /** @brief TCOOLTHRS (20 bit): lower velocity limit for CoolStep and the DIAG stall
     *         output; 0 disables. @return false if out of range. */
    bool setCoolstepThreshold(uint32_t tcoolthrs);
    /** @brief COOLCONF: semin 0-15 (0 disables CoolStep), semax 0-15, seup 0-3, sedn 0-3,
     *         seimin 0 (half IRUN minimum) or 1 (quarter). @return false if out of range. */
    bool configureCoolstep(uint8_t semin, uint8_t semax, uint8_t seup, uint8_t sedn, uint8_t seimin);
    /** @brief SG_RESULT 0-510 (higher means less load). */
    uint16_t readStallguard();
    /** @brief DIAG pin level (stall or driver error); false and ok() false without a diag pin. */
    bool isStalled();
    /** @brief Pulse the STEP pin @p count times (negative = reverse via DIR).
     *  @param count   Pulses; sign gives direction.
     *  @param delayS  High and low time of each pulse in seconds.
     *  @return false if no step pin (or no dir pin for a negative count).
     */
    bool step(int32_t count, float delayS);
    /** @brief MSCNT 0-1023. */
    uint16_t microstepPosition();
    /** @brief DRV_STATUS.CS_ACTUAL 0-31. */
    uint8_t readCurrentScale();
    /** @brief Signed MSCURACT table values in register order. */
    CoilCurrents readCoilCurrents();
    /** @brief TSTEP (20 bit). */
    uint32_t readTstep();
    /** @brief DRV_STATUS t120..t157 as bits 0..3. */
    uint8_t readTemperatureFlags();
    /** @brief Decoded DRV_STATUS. */
    Status readStatus();
    /** @brief Decoded GSTAT; @p clear writes the flags back. */
    GlobalStatus readGlobalStatus(bool clear = false);
    /** @brief IFCNT write counter. */
    uint8_t readIfcnt();
    /** @brief IOIN raw word, decoded pin states and VERSION. */
    Ioin readIoin();
    /** @brief OTP_READ 24-bit word. */
    uint32_t readOtp();
    /** @brief PWM_SCALE / PWM_AUTO results. */
    PwmAuto readPwmAuto();

private:
    OutputPin* _stepPin;
    OutputPin* _dirPin;
    InputPin* _diagPin;
};
