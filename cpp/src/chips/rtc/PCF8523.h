#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief PCF8523 low-power I²C real-time clock and calendar — minimal interface.
 *
 *  Battery-backed calendar clock (seconds..year) with sensible defaults:
 *  24-hour time format, battery switch-over in standard mode with
 *  battery-low detection enabled (PM[2:0]=000, written by the constructor).
 *  Fixed I²C address 0x68.
 *
 *  Weekday convention: 0=Sunday..6=Saturday (the datasheet's suggested
 *  assignment — the WEEKDAYS register has no hardware-enforced meaning).
 *
 *  @param connection Configured I²C connection pointing at the device.
 */
class PCF8523Minimal {
public:
    /** @brief Fixed 7-bit I²C address — the PCF8523 has no address pins. */
    static constexpr uint8_t I2C_ADDRESS = 0x68;

    /** @brief Calendar clock value. `weekday` is 0=Sunday..6=Saturday;
     *  `hour` is 0–23; `year` is the full year (2000–2099). */
    struct DateTime {
        uint16_t year;
        uint8_t  month;
        uint8_t  day;
        uint8_t  weekday;
        uint8_t  hour;
        uint8_t  minute;
        uint8_t  second;
    };

    /** @brief Confirm the device answers (reads CONTROL_1) and enable
     *  battery switch-over standard mode with battery-low detection. */
    explicit PCF8523Minimal(Connection& connection);

    /** @brief Read the current calendar clock value. */
    void getDatetime(DateTime& dt);

    /** @brief Write the calendar clock using the STOP-bit precision start.
     *
     *  Freezes the divider chain (STOP=1), writes all seven time/date
     *  registers in one transaction, then releases STOP. Forces 24-hour mode
     *  and clears the OS flag (the time is now known-good). */
    void setDatetime(const DateTime& dt);

protected:
    static constexpr uint8_t REG_CONTROL_1       = 0x00;
    static constexpr uint8_t REG_CONTROL_2       = 0x01;
    static constexpr uint8_t REG_CONTROL_3       = 0x02;
    static constexpr uint8_t REG_SECONDS         = 0x03;
    static constexpr uint8_t REG_MINUTES         = 0x04;
    static constexpr uint8_t REG_HOURS           = 0x05;
    static constexpr uint8_t REG_DAYS            = 0x06;
    static constexpr uint8_t REG_WEEKDAYS        = 0x07;
    static constexpr uint8_t REG_MONTHS          = 0x08;
    static constexpr uint8_t REG_YEARS           = 0x09;
    static constexpr uint8_t REG_MINUTE_ALARM    = 0x0A;
    static constexpr uint8_t REG_HOUR_ALARM      = 0x0B;
    static constexpr uint8_t REG_DAY_ALARM       = 0x0C;
    static constexpr uint8_t REG_WEEKDAY_ALARM   = 0x0D;
    static constexpr uint8_t REG_OFFSET          = 0x0E;
    static constexpr uint8_t REG_TMR_CLKOUT_CTRL = 0x0F;
    static constexpr uint8_t REG_TMR_A_FREQ_CTRL = 0x10;
    static constexpr uint8_t REG_TMR_A_REG       = 0x11;
    static constexpr uint8_t REG_TMR_B_FREQ_CTRL = 0x12;
    static constexpr uint8_t REG_TMR_B_REG       = 0x13;

    // CONTROL_1 (0x00) bits.
    static constexpr uint8_t C1_T     = 0x40;
    static constexpr uint8_t C1_STOP  = 0x20;
    static constexpr uint8_t C1_SR    = 0x10;
    static constexpr uint8_t C1_12_24 = 0x08;
    static constexpr uint8_t C1_SIE   = 0x04;
    static constexpr uint8_t C1_AIE   = 0x02;

    // CONTROL_2 (0x01) bits.
    static constexpr uint8_t C2_WTAF      = 0x80;
    static constexpr uint8_t C2_CTAF      = 0x40;
    static constexpr uint8_t C2_CTBF      = 0x20;
    static constexpr uint8_t C2_SF        = 0x10;
    static constexpr uint8_t C2_AF        = 0x08;
    static constexpr uint8_t C2_WTAIE     = 0x04;
    static constexpr uint8_t C2_CTAIE     = 0x02;
    static constexpr uint8_t C2_CTBIE     = 0x01;
    static constexpr uint8_t C2_CLEARABLE = 0x78;  // CTAF|CTBF|SF|AF: write 0 clears, 1 keeps
    static constexpr uint8_t C2_ENABLES   = 0x07;

    // CONTROL_3 (0x02) bits.
    static constexpr uint8_t C3_PM_MASK = 0xE0;
    static constexpr uint8_t C3_BSF     = 0x08;
    static constexpr uint8_t C3_BLF     = 0x04;
    static constexpr uint8_t C3_BSIE    = 0x02;
    static constexpr uint8_t C3_BLIE    = 0x01;

    static constexpr uint8_t SECONDS_OS = 0x80;

    Connection& _connection;

    void _writeReg(uint8_t reg, uint8_t value);
    uint8_t _readReg(uint8_t reg);
    void _writeRegs(uint8_t startReg, const uint8_t* data, size_t len);
    void _readRegs(uint8_t startReg, uint8_t* buf, size_t len);
    uint8_t _readControl1();

    static uint8_t _bcdToInt(uint8_t bcd);
    static uint8_t _intToBcd(uint8_t value);
};

/** @brief PCF8523 full interface — extends Minimal with the alarm, Timer A
 *  (countdown or watchdog), Timer B, programmable CLKOUT, offset
 *  calibration, battery backup control/status, oscillator-stop detection,
 *  software reset, and the Level-3 interrupt API.
 *
 *  `INT1` is shared with CLKOUT: interrupts only reach `INT1` once CLKOUT is
 *  disabled (disableClockOutput()); the driver never does that implicitly.
 *  Timer B additionally drives the dedicated `INT2` pin.
 *
 *  @param connection Configured I²C connection pointing at the device.
 */
class PCF8523Full : public PCF8523Minimal {
public:
    /** @brief Interrupt source bit — second tick. */
    static constexpr uint8_t SOURCE_SECOND         = 0x01;
    /** @brief Interrupt source bit — Timer A timed out (countdown or watchdog). */
    static constexpr uint8_t SOURCE_TIMER_A        = 0x02;
    /** @brief Interrupt source bit — Timer B timed out. */
    static constexpr uint8_t SOURCE_TIMER_B        = 0x04;
    /** @brief Interrupt source bit — all enabled alarm fields matched. */
    static constexpr uint8_t SOURCE_ALARM          = 0x08;
    /** @brief Interrupt source bit — battery switch-over occurred. */
    static constexpr uint8_t SOURCE_BATTERY_SWITCH = 0x10;
    /** @brief Interrupt source bit — battery low. */
    static constexpr uint8_t SOURCE_BATTERY_LOW    = 0x20;

    /** @brief Alarm field value meaning "field disabled / ignored in the match". */
    static constexpr uint8_t ALARM_DISABLED = 0xFF;

    /** @brief Timer A operating mode. */
    enum class TimerAMode : uint8_t { Countdown, Watchdog };

    /** @brief Timer source clock (TAQ/TBQ encoding). */
    enum class SourceClock : uint8_t {
        Hz4096   = 0x00,  ///< 4096 Hz — 244 µs … 62.256 ms
        Hz64     = 0x01,  ///< 64 Hz — 15.625 ms … 3.984 s
        Hz1      = 0x02,  ///< 1 Hz — 1 s … 255 s
        Hz1_60   = 0x03,  ///< 1/60 Hz — 1 min … 255 min
        Hz1_3600 = 0x07,  ///< 1/3600 Hz — 1 h … 255 h
    };

    /** @brief Offset correction interval. */
    enum class OffsetMode : uint8_t {
        EveryTwoHours,  ///< 4.34 ppm per LSB (default)
        EveryMinute,    ///< 4.069 ppm per LSB
    };

    /** @brief Battery switch-over mode (PM[2:0] together with low detection). */
    enum class BatteryMode : uint8_t {
        Standard,  ///< switch when VDD < VBAT and VDD < 2.5 V
        Direct,    ///< switch whenever VDD < VBAT
        Disabled,  ///< VDD only — tie VBAT to VDD
    };

    /** @brief Alarm configuration; a field set to ALARM_DISABLED is ignored. */
    struct Alarm {
        uint8_t minute;
        uint8_t hour;
        uint8_t day;
        uint8_t weekday;
    };

    explicit PCF8523Full(Connection& connection);

    /** @brief Decode the alarm registers (0x0A–0x0D); disabled fields read
     *  as ALARM_DISABLED. */
    void getAlarm(Alarm& alarm);
    /** @brief Write the alarm registers (0x0A–0x0D). Fields set to
     *  ALARM_DISABLED are ignored; the alarm fires when every enabled field
     *  matches. */
    void setAlarm(const Alarm& alarm);

    /** @brief Configure and start Timer A.
     *  @param mode Countdown or watchdog.
     *  @param value Countdown value, 0–255.
     *  @param sourceClock Timer tick frequency.
     *  @param pulsed Pulsed (true) or permanently-active (false) interrupt. */
    void configureTimerA(TimerAMode mode, uint8_t value, SourceClock sourceClock, bool pulsed = false);
    /** @brief Stop Timer A (TAC=00). */
    void disableTimerA();
    /** @brief Read Timer A's live countdown value (not the loaded one). */
    uint8_t readTimerA();

    /** @brief Configure and start Timer B (also drives INT2).
     *  @param value Countdown value, 0–255.
     *  @param sourceClock Timer tick frequency.
     *  @param pulseWidthMs Pulsed-mode low-pulse width in ms; the nearest of
     *         the eight hardware widths (46.875–218.75 ms) is used.
     *  @param pulsed Pulsed (true) or permanently-active (false) interrupt. */
    void configureTimerB(uint8_t value, SourceClock sourceClock, float pulseWidthMs = 46.875f, bool pulsed = false);
    /** @brief Stop Timer B (TBC=0). */
    void disableTimerB();
    /** @brief Read Timer B's live countdown value (not the loaded one). */
    uint8_t readTimerB();

    /** @brief Drive CLKOUT on the shared INT1/CLKOUT pin.
     *  @param frequencyHz One of 32768, 16384, 8192, 4096, 1024, 32, 1 Hz
     *         (other values disable CLKOUT). */
    void setClockOutput(uint32_t frequencyHz);
    /** @brief Disable CLKOUT (COF=111), freeing INT1 for interrupts. */
    void disableClockOutput();

    /** @brief Read the clock-offset calibration register.
     *  @param offset Receives the two's-complement correction, −64…+63 LSB.
     *  @param mode Receives the correction interval. */
    void getOffset(int8_t& offset, OffsetMode& mode);
    /** @brief Write the clock-offset calibration register.
     *  @param offset Two's-complement correction, −64…+63 LSB.
     *  @param mode Correction interval (4.34 or 4.069 ppm per LSB). */
    void setOffset(int8_t offset, OffsetMode mode = OffsetMode::EveryTwoHours);

    /** @brief Select the battery switch-over mode (PM[2:0]).
     *  @param mode Standard, direct or disabled switch-over.
     *  @param lowDetection Enable battery-low detection. */
    void configureBatteryBackup(BatteryMode mode, bool lowDetection = true);
    /** @brief Read BSF — true if a switch-over to VBAT occurred since it was last cleared. */
    bool isBatterySwitchedOver();
    /** @brief Clear BSF only, leaving PM and the enable bits unchanged. */
    void clearBatterySwitchover();
    /** @brief Read BLF (read-only) — true if VBAT is below the threshold. */
    bool isBatteryLow();

    /** @brief Read the OS flag (bit 7 of SECONDS) — true means the time may
     *  be invalid; cleared by setDatetime(). */
    bool oscillatorStopped();

    /** @brief Send the software-reset sequence (0x58 to CONTROL_1). Resets
     *  all control/configuration registers to POR defaults — including
     *  PM=111 (battery backup disabled) — but keeps the time/date/alarm/timer
     *  values. */
    void softwareReset();

    /** @brief Subscribe to interrupts. Call disableClockOutput() first if
     *  INT1 is still carrying CLKOUT.
     *  @param callback Called with the pre-clear status mask (test with SOURCE_*).
     *  @param intPin Optional InputPin for this call, overriding connection.intPin(). */
    void onInterrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);
    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();
    /** @brief Read CONTROL_2/CONTROL_3, clear the set CTAF/CTBF/SF/AF/BSF
     *  flags (WTAF/BLF are read-only; enable bits untouched), and return the
     *  pre-clear status mask — test with the SOURCE_* constants. */
    uint8_t pollInterrupt();
    /** @brief Enable one or more interrupt sources. SOURCE_TIMER_A sets
     *  WTAIE or CTAIE depending on Timer A's configured mode.
     *  @param source Bitwise OR of SOURCE_* constants. */
    void enableInterrupt(uint8_t source);
    /** @brief Disable one or more interrupt sources.
     *  @param source Bitwise OR of SOURCE_* constants. */
    void disableInterrupt(uint8_t source);

protected:
    static constexpr uint8_t TMR_TAM           = 0x80;
    static constexpr uint8_t TMR_TBM           = 0x40;
    static constexpr uint8_t TMR_COF_MASK      = 0x38;
    static constexpr uint8_t TMR_TAC_MASK      = 0x06;
    static constexpr uint8_t TMR_TAC_COUNTDOWN = 0x02;
    static constexpr uint8_t TMR_TAC_WATCHDOG  = 0x04;
    static constexpr uint8_t TMR_TBC           = 0x01;

    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;

    static PCF8523Full* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();

    void _updateTmrClkout(uint8_t clearMask, uint8_t setBits);
    void _writeControl2(uint8_t enables);
    void _writeControl3(uint8_t value);
    void _setInterruptEnables(uint8_t source, bool enable);
};
