#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCF8523.h"

// Scheduling core of a battery-backed logger: reseeds the clock after a
// power loss, checks the coin cell, then wakes on an hourly alarm to print
// a timestamp while Timer B pulses a 30-second "still running" heartbeat
// on INT2 that toggles an LED.

static volatile uint8_t pendingStatus = 0;
static bool ledOn = false;

static void onEvent(uint8_t status) {
    pendingStatus |= status;
}

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = PCF8523Minimal::I2C_ADDRESS,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    PCF8523Full rtc(connection);                             // Create PCF8523 Full driver, (connection)

    // --- Detect a lost time reference and reseed if needed ---
    // A fresh chip, or one whose backup cell was disconnected too long, reports
    // the OS flag set: its calendar cannot be trusted until it is reseeded.
    if (rtc.oscillatorStopped()) {                           // Query oscillator-stop flag, () → bool
        PCF8523Minimal::DateTime reference{2026, 1, 1, 4, 0, 0, 0};
        rtc.setDatetime(reference);                          // Write calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → void
        printf("oscillator was stopped - reseeded from reference timestamp\n");
    }

    // --- Keep the clock alive through power cuts ---
    // Standard switch-over is already the driver default; it is repeated here
    // so the logger's power policy is explicit. A low coin cell is reported
    // once so it can be replaced before the next outage.
    rtc.configureBatteryBackup(PCF8523Full::BatteryMode::Standard);  // Select battery switch-over, (mode, lowDetection=true) → void
    if (rtc.isBatteryLow()) {                                // Query battery-low flag, () → bool
        printf("warning: backup battery low - replace the coin cell\n");
    }

    // --- Hourly wake-up plus a 30 s heartbeat ---
    // Only the minute field is enabled, so the alarm matches at hh:00 every
    // hour. Timer B reloads automatically and has its own INT2 pin, so the
    // heartbeat keeps running independently of the hourly alarm.
    rtc.disableClockOutput();                                // Disable CLKOUT, () → void
    PCF8523Full::Alarm hourly{0, PCF8523Full::ALARM_DISABLED, PCF8523Full::ALARM_DISABLED, PCF8523Full::ALARM_DISABLED};
    rtc.setAlarm(hourly);                                    // Configure alarm, (minute, hour, day, weekday; ALARM_DISABLED = ignore) → void
    rtc.configureTimerB(30, PCF8523Full::SourceClock::Hz1);  // Start Timer B, (value 0–255, sourceClock, pulseWidthMs=46.875 ms, pulsed=false) → void

    rtc.onInterrupt(onEvent);                                // Subscribe to interrupts, (callback, intPin=nullptr) → void
    rtc.enableInterrupt(PCF8523Full::SOURCE_ALARM | PCF8523Full::SOURCE_TIMER_B);  // Enable sources, (source) → void

    // --- Dispatch by source: log on the alarm, blink on the heartbeat ---
    // Without an INT pin on the connection, poll the flags instead.
    int alarms = 0;
    while (alarms < 3) {
        if (!connection.intPin()) pendingStatus |= rtc.pollInterrupt();  // Poll & clear flags, () → uint8_t
        uint8_t status = pendingStatus;
        pendingStatus = 0;
        if (status & PCF8523Full::SOURCE_ALARM) {
            PCF8523Minimal::DateTime dt;
            rtc.getDatetime(dt);                             // Read calendar clock, () → DateTime
            printf("[hourly] %04u-%02u-%02u %02u:%02u:%02u\n",
                dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);
            alarms++;
        }
        if (status & PCF8523Full::SOURCE_TIMER_B) {
            ledOn = !ledOn;
            printf("[heartbeat] LED %s\n", ledOn ? "on" : "off");
        }
        vTaskDelay(pdMS_TO_TICKS(200));
    }

    // --- Leave the chip quiet on exit ---
    rtc.disableInterrupt(PCF8523Full::SOURCE_ALARM | PCF8523Full::SOURCE_TIMER_B);  // Disable sources, (source) → void
    rtc.disableTimerB();                                     // Stop Timer B, () → void
    rtc.offInterrupt();                                      // Unsubscribe, () → void
}
