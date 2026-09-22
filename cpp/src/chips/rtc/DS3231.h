#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"

/** @brief DS3231 extremely accurate I²C-integrated RTC/TCXO/crystal — minimal interface.
 *
 *  Battery-backed calendar clock (seconds..year) plus a free on-chip
 *  temperature reading, with sensible defaults: 24-hour time format,
 *  oscillator continuously enabled on VBAT. Fixed I²C address 0x68.
 *
 *  @param connection Configured I²C connection pointing at the device.
 */
class DS3231Minimal {
public:
    /** @brief Fixed 7-bit I²C address — the DS3231 has no address pins. */
    static constexpr uint8_t I2C_ADDRESS = 0x68;

    /** @brief Calendar clock value. `weekday` is ISO 8601: 1=Monday..7=Sunday.
     *  `year` is the full year (2000–2099; the driver assumes the 2000s century). */
    struct DateTime {
        uint16_t year;
        uint8_t  month;
        uint8_t  day;
        uint8_t  weekday;
        uint8_t  hour;
        uint8_t  minute;
        uint8_t  second;
    };

    explicit DS3231Minimal(Connection& connection);

    /** @brief Read the current calendar clock value. */
    void getDatetime(DateTime& dt);

    /** @brief Write the calendar clock. Forces 24-hour mode and clears the
     *  Oscillator Stop Flag (the time is now known-good). */
    void setDatetime(const DateTime& dt);

    /** @brief Read the last completed temperature conversion.
     *
     *  No forced conversion — the chip converts autonomously every 64 s and
     *  on power-up, so this may be up to 64 s stale.
     *
     *  @return Temperature in °C.
     */
    float readTemperature();

protected:
    static constexpr uint8_t REG_SECONDS        = 0x00;
    static constexpr uint8_t REG_MINUTES        = 0x01;
    static constexpr uint8_t REG_HOURS          = 0x02;
    static constexpr uint8_t REG_DAY            = 0x03;
    static constexpr uint8_t REG_DATE           = 0x04;
    static constexpr uint8_t REG_MONTH_CENTURY  = 0x05;
    static constexpr uint8_t REG_YEAR           = 0x06;
    static constexpr uint8_t REG_ALARM1_SECONDS = 0x07;
    static constexpr uint8_t REG_ALARM1_MINUTES = 0x08;
    static constexpr uint8_t REG_ALARM1_HOURS   = 0x09;
    static constexpr uint8_t REG_ALARM1_DAYDATE = 0x0A;
    static constexpr uint8_t REG_ALARM2_MINUTES = 0x0B;
    static constexpr uint8_t REG_ALARM2_HOURS   = 0x0C;
    static constexpr uint8_t REG_ALARM2_DAYDATE = 0x0D;
    static constexpr uint8_t REG_CONTROL        = 0x0E;
    static constexpr uint8_t REG_STATUS         = 0x0F;
    static constexpr uint8_t REG_AGING_OFFSET   = 0x10;
    static constexpr uint8_t REG_TEMP_MSB       = 0x11;
    static constexpr uint8_t REG_TEMP_LSB       = 0x12;

    // CONTROL (0x0E) bits.
    static constexpr uint8_t CTRL_EOSC  = 0x80;
    static constexpr uint8_t CTRL_BBSQW = 0x40;
    static constexpr uint8_t CTRL_CONV  = 0x20;
    static constexpr uint8_t CTRL_RS2   = 0x10;
    static constexpr uint8_t CTRL_RS1   = 0x08;
    static constexpr uint8_t CTRL_INTCN = 0x04;
    static constexpr uint8_t CTRL_A2IE  = 0x02;
    static constexpr uint8_t CTRL_A1IE  = 0x01;

    // CONTROL_STATUS (0x0F) bits.
    static constexpr uint8_t STAT_OSF     = 0x80;
    static constexpr uint8_t STAT_EN32KHZ = 0x08;
    static constexpr uint8_t STAT_BSY     = 0x04;
    static constexpr uint8_t STAT_A2F     = 0x02;
    static constexpr uint8_t STAT_A1F     = 0x01;

    Connection& _connection;

    void _writeReg(uint8_t reg, uint8_t value);
    uint8_t _readReg(uint8_t reg);
    void _writeRegs(uint8_t startReg, const uint8_t* data, size_t len);
    void _readRegs(uint8_t startReg, uint8_t* buf, size_t len);
    void _delayMs(uint32_t ms);

    static uint8_t _bcdToInt(uint8_t bcd);
    static uint8_t _intToBcd(uint8_t value);
    static uint8_t _decodeHour(uint8_t raw);
};

/** @brief DS3231 full interface — extends Minimal with both alarms,
 *  square-wave/32kHz outputs, oscillator/aging control, forced temperature
 *  conversion, and the Level-2 selectable-source interrupt API.
 *
 *  `INT/SQW` is one physical pin multiplexed by the CONTROL register's
 *  INTCN bit — enableInterrupt()/onInterrupt() and enableSquareWave() are
 *  mutually exclusive; whichever call happens last wins. pollInterrupt()
 *  still reports alarm matches correctly regardless of INTCN, since A1F/A2F
 *  latch independently of the pin's mode.
 *
 *  @param connection Configured I²C connection pointing at the device.
 */
class DS3231Full : public DS3231Minimal {
public:
    /** @brief Interrupt source bit — Alarm 1 matched. */
    static constexpr uint8_t SOURCE_ALARM1 = 0x01;
    /** @brief Interrupt source bit — Alarm 2 matched. */
    static constexpr uint8_t SOURCE_ALARM2 = 0x02;

    /** @brief Alarm 1 match granularity (datasheet Table 2). */
    static constexpr uint8_t ALARM1_EVERY_SECOND                    = 0;
    static constexpr uint8_t ALARM1_MATCH_SECONDS                   = 1;
    static constexpr uint8_t ALARM1_MATCH_MINUTES_SECONDS            = 2;
    static constexpr uint8_t ALARM1_MATCH_HOURS_MINUTES_SECONDS      = 3;
    static constexpr uint8_t ALARM1_MATCH_DATE_HOURS_MINUTES_SECONDS = 4;
    static constexpr uint8_t ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS  = 5;

    /** @brief Alarm 2 match granularity (datasheet Table 2). */
    static constexpr uint8_t ALARM2_EVERY_MINUTE              = 0;
    static constexpr uint8_t ALARM2_MATCH_MINUTES              = 1;
    static constexpr uint8_t ALARM2_MATCH_HOURS_MINUTES         = 2;
    static constexpr uint8_t ALARM2_MATCH_DATE_HOURS_MINUTES    = 3;
    static constexpr uint8_t ALARM2_MATCH_DAY_HOURS_MINUTES     = 4;

    /** @brief Alarm 1 configuration. `dayOrDate`/`isDayOfWeek` are ignored
     *  for the mask-only modes (`ALARM1_EVERY_SECOND`..`ALARM1_MATCH_HOURS_MINUTES_SECONDS`). */
    struct Alarm1 {
        uint8_t second;
        uint8_t minute;
        uint8_t hour;
        uint8_t dayOrDate;
        bool    isDayOfWeek;
        uint8_t matchMode;
    };

    /** @brief Alarm 2 configuration. `dayOrDate`/`isDayOfWeek` are ignored
     *  for the mask-only modes (`ALARM2_EVERY_MINUTE`..`ALARM2_MATCH_HOURS_MINUTES`). */
    struct Alarm2 {
        uint8_t minute;
        uint8_t hour;
        uint8_t dayOrDate;
        bool    isDayOfWeek;
        uint8_t matchMode;
    };

    explicit DS3231Full(Connection& connection);

    /** @brief Decode the Alarm 1 registers (0x07–0x0A). */
    void getAlarm1(Alarm1& alarm);
    /** @brief Write the Alarm 1 registers (0x07–0x0A). */
    void setAlarm1(const Alarm1& alarm);
    /** @brief Decode the Alarm 2 registers (0x0B–0x0D). */
    void getAlarm2(Alarm2& alarm);
    /** @brief Write the Alarm 2 registers (0x0B–0x0D). */
    void setAlarm2(const Alarm2& alarm);

    /** @brief Drive INT/SQW as a square wave. Sets INTCN=0 (mutually
     *  exclusive with alarm interrupts).
     *  @param rateHz One of 1, 1024, 4096, 8192 (default; nearest match used otherwise).
     *  @param batteryBacked Keep the square wave driven while running on VBAT. */
    void enableSquareWave(uint32_t rateHz = 8192, bool batteryBacked = false);
    /** @brief Return INT/SQW to interrupt mode (sets INTCN=1). */
    void disableSquareWave();

    /** @brief Read the EN32kHz bit — whether the separate 32kHz pin is driven. */
    bool is32kHzEnabled();
    /** @brief Enable the separate 32kHz output pin. */
    void enable32kHzOutput();
    /** @brief Disable the separate 32kHz output pin. */
    void disable32kHzOutput();

    /** @brief Read the Oscillator Stop Flag — true means timekeeping data
     *  may be invalid since the last check. */
    bool oscillatorStopped();
    /** @brief Clear the Oscillator Stop Flag, preserving EN32kHz. */
    void clearOscillatorStopped();

    /** @brief Keep the oscillator running while on VBAT (EOSC=0, power-on default). */
    void enableBatteryOscillator();
    /** @brief Stop the oscillator when switched to VBAT, saving battery current (EOSC=1). */
    void disableBatteryOscillator();

    /** @brief Force an immediate temperature conversion and block until it
     *  completes (polls BSY, max ~200 ms). */
    void forceTemperatureConversion();

    /** @brief Read the raw two's-complement oscillator trim code. Not a
     *  physically-scaled unit — see the spec's Implementation Notes. */
    int8_t getAgingOffset();
    /** @brief Write the raw two's-complement oscillator trim code. */
    void setAgingOffset(int8_t offset);

    /** @brief Subscribe to alarm interrupts. Sets INTCN=1 so INT/SQW
     *  carries alarm interrupts instead of the square wave.
     *  @param callback Called with the pre-clear status byte (mask with SOURCE_ALARM1/SOURCE_ALARM2).
     *  @param intPin Optional InputPin for this call, overriding connection.intPin(). */
    void onInterrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);
    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();
    /** @brief Read CONTROL_STATUS, clear A1F/A2F (leaving OSF/EN32kHz/BSY
     *  untouched), and return the pre-clear byte — mask with
     *  SOURCE_ALARM1/SOURCE_ALARM2 to test each source. */
    uint8_t pollInterrupt();
    /** @brief Enable one or both alarm interrupt sources and set INTCN=1.
     *  @param source Bitwise OR of SOURCE_ALARM1/SOURCE_ALARM2. */
    void enableInterrupt(uint8_t source);
    /** @brief Disable one or both alarm interrupt sources.
     *  @param source Bitwise OR of SOURCE_ALARM1/SOURCE_ALARM2. */
    void disableInterrupt(uint8_t source);

protected:
    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;

    static DS3231Full* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();

    static void _alarm1MaskBits(uint8_t matchMode, uint8_t& a1m1, uint8_t& a1m2,
                                uint8_t& a1m3, uint8_t& a1m4, uint8_t& dydt);
    static void _alarm2MaskBits(uint8_t matchMode, uint8_t& a2m2, uint8_t& a2m3,
                                uint8_t& a2m4, uint8_t& dydt);
    static uint8_t _alarm1MatchMode(uint8_t a1m1, uint8_t a1m2, uint8_t a1m3,
                                    uint8_t a1m4, uint8_t dydt);
    static uint8_t _alarm2MatchMode(uint8_t a2m2, uint8_t a2m3, uint8_t a2m4, uint8_t dydt);
};
