#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCF8523.h"

static void onEvent(uint8_t status) {
    printf("interrupt status=0x%02X\n", status);
}

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = PCF8523Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    PCF8523Full rtc(connection);                             // Create PCF8523 Full driver, (connection)
                                                             // enables battery switch-over standard mode (PM=000)

    PCF8523Minimal::DateTime dt{2026, 9, 23, 3, 14, 30, 0};
    rtc.setDatetime(dt);                                     // Write calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → void
                                                             // STOP-bit precision start; forces 24-hour mode and clears OS
    rtc.getDatetime(dt);                                     // Read calendar clock, () → DateTime
                                                             // decodes the seven BCD clock/calendar registers
    bool stopped = rtc.oscillatorStopped();                  // Query oscillator-stop flag, () → bool
                                                             // true means the time may be invalid until setDatetime

    PCF8523Full::Alarm alarm{0, 9, PCF8523Full::ALARM_DISABLED, PCF8523Full::ALARM_DISABLED};
    rtc.setAlarm(alarm);                                     // Configure alarm, (minute, hour, day, weekday; ALARM_DISABLED = ignore) → void
                                                             // fires daily at 09:00
    rtc.getAlarm(alarm);                                     // Read alarm, () → Alarm
                                                             // disabled fields decode as ALARM_DISABLED

    rtc.configureTimerA(PCF8523Full::TimerAMode::Countdown, 10, PCF8523Full::SourceClock::Hz1);  // Start Timer A, (mode, value 0–255, sourceClock, pulsed=false) → void
                                                             // counts down 10 s, then sets CTAF
    uint8_t remainingA = rtc.readTimerA();                   // Read Timer A counter, () → uint8_t
                                                             // live value, not the loaded one
    rtc.disableTimerA();                                     // Stop Timer A, () → void

    rtc.configureTimerB(30, PCF8523Full::SourceClock::Hz1, 62.5f, true);  // Start Timer B, (value 0–255, sourceClock, pulseWidthMs=46.875 ms, pulsed=false) → void
                                                             // 30 s countdown, pulsed 62.5 ms low on INT1 and INT2
    uint8_t remainingB = rtc.readTimerB();                   // Read Timer B counter, () → uint8_t
    rtc.disableTimerB();                                     // Stop Timer B, () → void

    rtc.setClockOutput(1);                                   // Drive CLKOUT, (frequencyHz) → void
                                                             // 1 Hz square wave on the shared INT1/CLKOUT pin
    rtc.disableClockOutput();                                // Disable CLKOUT, () → void
                                                             // frees INT1 for interrupts

    rtc.setOffset(-3, PCF8523Full::OffsetMode::EveryTwoHours);  // Write offset calibration, (offset −64–63, mode=EveryTwoHours) → void
                                                             // −3 LSB × 4.34 ppm = −13.02 ppm correction
    int8_t offset;
    PCF8523Full::OffsetMode offsetMode;
    rtc.getOffset(offset, offsetMode);                       // Read offset calibration, () → (int8_t, OffsetMode)

    rtc.configureBatteryBackup(PCF8523Full::BatteryMode::Standard, true);  // Select battery switch-over, (mode, lowDetection=true) → void
                                                             // switches to VBAT when VDD < VBAT and VDD < 2.5 V
    bool switched = rtc.isBatterySwitchedOver();             // Query switch-over flag, () → bool
    rtc.clearBatterySwitchover();                            // Clear switch-over flag, () → void
    bool low = rtc.isBatteryLow();                           // Query battery-low flag, () → bool
                                                             // read-only; clears itself once the cell is replaced

    rtc.onInterrupt(onEvent);                                // Subscribe to interrupts, (callback, intPin=nullptr) → void
                                                             // needs an INT pin on the connection or the intPin argument
    rtc.enableInterrupt(PCF8523Full::SOURCE_ALARM | PCF8523Full::SOURCE_TIMER_B | PCF8523Full::SOURCE_BATTERY_LOW);  // Enable sources, (source) → void
                                                             // sets AIE, CTBIE and BLIE
    uint8_t status = rtc.pollInterrupt();                    // Poll & clear flags, () → uint8_t
                                                             // clears CTAF/CTBF/SF/AF/BSF, returns the pre-clear mask
    rtc.disableInterrupt(PCF8523Full::SOURCE_ALARM | PCF8523Full::SOURCE_TIMER_A | PCF8523Full::SOURCE_TIMER_B | PCF8523Full::SOURCE_BATTERY_LOW);  // Disable sources, (source) → void
    rtc.offInterrupt();                                      // Unsubscribe, () → void

    rtc.softwareReset();                                     // Software reset, () → void
                                                             // control registers back to POR (PM=111); time is kept

    printf("%04u-%02u-%02u %02u:%02u:%02u os_stopped=%d\n",
        dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second, stopped);
    printf("alarm %u:%u timerA=%u timerB=%u\n", alarm.hour, alarm.minute, remainingA, remainingB);
    printf("offset=%d every_minute=%d switched=%d low=%d status=0x%02X\n",
        offset, offsetMode == PCF8523Full::OffsetMode::EveryMinute, switched, low, status);
}
