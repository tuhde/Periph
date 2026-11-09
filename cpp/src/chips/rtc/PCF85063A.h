#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/RegisterConnection.h"

/** @brief PCF85063A low-power I²C real-time clock and calendar — minimal interface.
 *
 *  Calendar clock (seconds..year) with sensible defaults: 24-hour time
 *  format and the clock running (STOP=0), enforced by the constructor.
 *  Fixed I²C address 0x51. There is no battery switch-over — the chip is fed
 *  from a single VDD supply. The constructor writes no time register and
 *  does not reset the chip.
 *
 *  Weekday convention: 0=Sunday..6=Saturday (the datasheet's suggested
 *  assignment — the Weekdays register has no hardware-enforced meaning).
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device.
 */
class PCF85063AMinimal {
public:
    /** @brief Fixed 7-bit I²C address — the PCF85063A has no address pins. */
    static constexpr uint8_t I2C_ADDRESS = 0x51;

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

    /** @brief Confirm the device answers (reads Control_1) and force
     *  24-hour mode with STOP=0. */
    explicit PCF85063AMinimal(RegisterConnection& connection);

    /** @brief Read the current calendar clock value (one 7-byte burst). */
    void getDatetime(DateTime& dt);

    /** @brief Write the calendar clock using the STOP-bit precision start.
     *
     *  Freezes the divider chain (STOP=1), writes all seven time/date
     *  registers in one transaction, then releases STOP. Forces 24-hour mode
     *  and clears the OS flag (the time is now known-good).
     *  @return false (and writes nothing) if `dt.year` is outside 2000–2099. */
    bool setDatetime(const DateTime& dt);

protected:
    static constexpr uint8_t REG_CONTROL_1     = 0x00;
    static constexpr uint8_t REG_CONTROL_2     = 0x01;
    static constexpr uint8_t REG_OFFSET        = 0x02;
    static constexpr uint8_t REG_RAM           = 0x03;
    static constexpr uint8_t REG_SECONDS       = 0x04;
    static constexpr uint8_t REG_MINUTES       = 0x05;
    static constexpr uint8_t REG_HOURS         = 0x06;
    static constexpr uint8_t REG_DAYS          = 0x07;
    static constexpr uint8_t REG_WEEKDAYS      = 0x08;
    static constexpr uint8_t REG_MONTHS        = 0x09;
    static constexpr uint8_t REG_YEARS         = 0x0A;
    static constexpr uint8_t REG_SECOND_ALARM  = 0x0B;
    static constexpr uint8_t REG_MINUTE_ALARM  = 0x0C;
    static constexpr uint8_t REG_HOUR_ALARM    = 0x0D;
    static constexpr uint8_t REG_DAY_ALARM     = 0x0E;
    static constexpr uint8_t REG_WEEKDAY_ALARM = 0x0F;
    static constexpr uint8_t REG_TIMER_VALUE   = 0x10;
    static constexpr uint8_t REG_TIMER_MODE    = 0x11;

    // Control_1 (0x00) bits.
    static constexpr uint8_t C1_STOP    = 0x20;
    static constexpr uint8_t C1_SR      = 0x10;
    static constexpr uint8_t C1_CIE     = 0x04;
    static constexpr uint8_t C1_12_24   = 0x02;
    static constexpr uint8_t C1_CAP_SEL = 0x01;

    // Control_2 (0x01) bits.
    static constexpr uint8_t C2_AIE   = 0x80;
    static constexpr uint8_t C2_AF    = 0x40;
    static constexpr uint8_t C2_MI    = 0x20;
    static constexpr uint8_t C2_HMI   = 0x10;
    static constexpr uint8_t C2_TF    = 0x08;
    static constexpr uint8_t C2_COF   = 0x07;
    static constexpr uint8_t C2_FLAGS = 0x48;  // AF|TF: write 0 clears, 1 keeps

    // Timer_mode (0x11) bits.
    static constexpr uint8_t TM_TCF_MASK = 0x18;
    static constexpr uint8_t TM_TE       = 0x04;
    static constexpr uint8_t TM_TIE      = 0x02;
    static constexpr uint8_t TM_TI_TP    = 0x01;

    static constexpr uint8_t SECONDS_OS = 0x80;

    RegisterConnection& _connection;

    void _writeReg(uint8_t reg, uint8_t value);
    uint8_t _readReg(uint8_t reg);
    void _writeRegs(uint8_t startReg, const uint8_t* data, size_t len);
    void _readRegs(uint8_t startReg, uint8_t* buf, size_t len);
    uint8_t _readControl1();

    static uint8_t _bcdToInt(uint8_t bcd);
    static uint8_t _intToBcd(uint8_t value);
};

/** @brief PCF85063A full interface — extends Minimal with the five-field
 *  alarm, the countdown timer, minute / half-minute interrupts,
 *  programmable CLKOUT, offset calibration, oscillator-load selection,
 *  oscillator-stop detection, clock stop/start, the free RAM byte, software
 *  reset and the interrupt API.
 *
 *  The `INT` pin is open-drain and active-low; CLKOUT is a separate pin.
 *
 *  @param connection RegisterConnection (I²C or SMBus) pointing at the device.
 */
class PCF85063AFull : public PCF85063AMinimal {
public:
    /** @brief Interrupt source bit — all enabled alarm fields matched (AF). */
    static constexpr uint8_t SOURCE_ALARM       = 0x01;
    /** @brief Interrupt source bit — countdown timer expired (TF). */
    static constexpr uint8_t SOURCE_TIMER       = 0x02;
    /** @brief Interrupt source bit — minute interrupt (enable only; reported as SOURCE_TIMER). */
    static constexpr uint8_t SOURCE_MINUTE      = 0x04;
    /** @brief Interrupt source bit — half-minute interrupt (enable only; reported as SOURCE_TIMER). */
    static constexpr uint8_t SOURCE_HALF_MINUTE = 0x08;
    /** @brief Interrupt source bit — offset-correction pulse (enable only, no flag). */
    static constexpr uint8_t SOURCE_CORRECTION  = 0x10;

    /** @brief Alarm field value meaning "field disabled / ignored in the match". */
    static constexpr uint8_t ALARM_DISABLED = 0xFF;

    /** @brief Timer source clock (TCF encoding). */
    enum class SourceClock : uint8_t {
        Hz4096 = 0x00,  ///< 4.096 kHz — 244 µs … 62.256 ms
        Hz64   = 0x01,  ///< 64 Hz — 15.625 ms … 3.984 s
        Hz1    = 0x02,  ///< 1 Hz — 1 s … 255 s
        Hz1_60 = 0x03,  ///< 1/60 Hz — 1 min … 4 h 15 min
    };

    /** @brief Offset correction mode. */
    enum class OffsetMode : uint8_t {
        Normal,  ///< correction every two hours, 4.34 ppm per LSB (default)
        Course,  ///< correction every four minutes, 4.069 ppm per LSB, higher power
    };

    /** @brief Minute / half-minute interrupt selection. */
    enum class MinuteInterrupt : uint8_t { Off, Minute, HalfMinute };

    /** @brief Alarm configuration; a field set to ALARM_DISABLED is ignored. */
    struct Alarm {
        uint8_t second;
        uint8_t minute;
        uint8_t hour;
        uint8_t day;
        uint8_t weekday;
    };

    explicit PCF85063AFull(RegisterConnection& connection);

    /** @brief Read the OS flag (bit 7 of Seconds) — true means the time may
     *  be invalid; cleared by setDatetime() or clearOscillatorStop(). */
    bool oscillatorStopped();
    /** @brief Clear OS, keeping the current seconds value. If
     *  oscillatorStopped() is still true afterwards the oscillator is not running. */
    void clearOscillatorStop();
    /** @brief Freeze the clock (STOP=1); the divider chain is held in reset. */
    void stopClock();
    /** @brief Release the clock (STOP=0). */
    void startClock();

    /** @brief Decode the alarm registers (0x0B–0x0F); disabled fields read
     *  as ALARM_DISABLED. */
    void getAlarm(Alarm& alarm);
    /** @brief Write the alarm registers (0x0B–0x0F) and clear AF. Fields set
     *  to ALARM_DISABLED are ignored; the alarm fires when every enabled field
     *  matches. */
    void setAlarm(const Alarm& alarm);

    /** @brief Configure and start the countdown timer. Disables the timer
     *  first, then loads value, source clock and mode and sets TE. Does not
     *  set TIE — use enableInterrupt(SOURCE_TIMER).
     *  @param value Countdown value, 1–255.
     *  @param sourceClock Timer tick frequency.
     *  @param pulsed Pulsed (true) or level (false, INT follows TF) interrupt. */
    void configureTimer(uint8_t value, SourceClock sourceClock, bool pulsed = false);
    /** @brief Stop the timer (TE=0, TIE=0) and select the 1/60 Hz clock for power saving. */
    void disableTimer();
    /** @brief Read the live countdown value (read until two consecutive reads agree). */
    uint8_t readTimer();

    /** @brief Select the minute / half-minute interrupt (requires offset mode Normal). */
    void setMinuteInterrupt(MinuteInterrupt mode);

    /** @brief Drive CLKOUT.
     *  @param frequencyHz One of 32768, 16384, 8192, 4096, 2048, 1024, 1 Hz
     *         (other values disable CLKOUT). */
    void setClockOutput(uint32_t frequencyHz);
    /** @brief Disable CLKOUT (COF=111, pin low) to save power. */
    void disableClockOutput();

    /** @brief Read the clock-offset calibration register.
     *  @param offset Receives the two's-complement correction, −64…+63 LSB.
     *  @param mode Receives the correction mode. */
    void getOffset(int8_t& offset, OffsetMode& mode);
    /** @brief Write the clock-offset calibration register.
     *  @param offset Two's-complement correction, −64…+63 LSB.
     *  @param mode Correction mode (4.34 or 4.069 ppm per LSB). */
    void setOffset(int8_t offset, OffsetMode mode = OffsetMode::Normal);

    /** @brief Select the oscillator load capacitance (CAP_SEL).
     *  @param picofarads 12.5 selects 12.5 pF; any other value selects 7 pF. */
    void setOscillatorLoad(float picofarads);

    /** @brief Read the free general-purpose RAM byte. */
    uint8_t readRam();
    /** @brief Write the free general-purpose RAM byte. */
    void writeRam(uint8_t value);

    /** @brief Send the software-reset sequence (0x58 to Control_1). Resets
     *  ALL registers including the time (2000-01-01 00:00:00, OS=1). */
    void softwareReset();

    /** @brief Subscribe to interrupts on the INT pin (falling edge).
     *  @param callback Called with the pre-clear status mask (SOURCE_ALARM / SOURCE_TIMER).
     *  @param intPin Optional InputPin for this call, overriding connection.intPin(). */
    void onInterrupt(void (*callback)(uint8_t status), InputPin* intPin = nullptr);
    /** @brief Unsubscribe and stop delivery. */
    void offInterrupt();
    /** @brief Read Control_2, clear the set AF/TF flags (enable bits and COF
     *  preserved), and return the pre-clear status mask — SOURCE_ALARM and/or
     *  SOURCE_TIMER. */
    uint8_t pollInterrupt();
    /** @brief Enable one or more interrupt sources (AIE, TIE, MI, HMI, CIE).
     *  @param source Bitwise OR of SOURCE_* constants. */
    void enableInterrupt(uint8_t source);
    /** @brief Disable one or more interrupt sources.
     *  @param source Bitwise OR of SOURCE_* constants. */
    void disableInterrupt(uint8_t source);

protected:
    void (*_callback)(uint8_t status) = nullptr;
    InputPin* _intPinUsed = nullptr;

    static PCF85063AFull* _activeInstance;
    static void _edgeTrampoline();
    void _handleEdge();

    void _updateControl1(uint8_t clearMask, uint8_t setBits);
    void _updateControl2(uint8_t clearMask, uint8_t setBits);
    void _updateTimerMode(uint8_t clearMask, uint8_t setBits);
    void _setInterruptEnables(uint8_t source, bool enable);
};
