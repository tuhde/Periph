//! Scheduling core of a battery-backed logger: reseeds the clock after a
//! power loss, checks the coin cell, then wakes on an hourly alarm to print
//! a timestamp while Timer B pulses a 30-second "still running" heartbeat
//! on INT2 that toggles an LED.

use linux_embedded_hal::I2cdev;
use periph::chips::rtc::{
    BatteryMode, Pcf8523Alarm, Pcf8523DateTime, Pcf8523Full, SourceClock,
    PCF8523_SOURCE_ALARM, PCF8523_SOURCE_TIMER_B,
};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut rtc = Pcf8523Full::new(dev, 0x68).expect("init PCF8523"); // Create PCF8523 Full driver, (i2c, addr=0x68)

    // --- Detect a lost time reference and reseed if needed ---
    // A fresh chip, or one whose backup cell was disconnected too long,
    // reports the OS flag set: its calendar cannot be trusted until reseeded.
    if rtc.oscillator_stopped().expect("oscillator_stopped") { // Query oscillator-stop flag, () → bool
        rtc.set_datetime(Pcf8523DateTime {
            year: 2026, month: 1, day: 1, weekday: 4, hour: 0, minute: 0, second: 0,
        }).expect("set_datetime");                      // Set the calendar clock, (datetime, weekday 0=Sun) → ()
        println!("oscillator was stopped - reseeded from reference timestamp");
    }

    // --- Keep the clock alive through power cuts ---
    // Standard switch-over is already the driver default; it is repeated here
    // so the logger's power policy is explicit. A low coin cell is reported
    // once so it can be replaced before the next outage.
    rtc.configure_battery_backup(BatteryMode::Standard, true)
        .expect("configure_battery_backup");            // Select battery switch-over, (mode, low_detection) → ()
    if rtc.is_battery_low().expect("is_battery_low") {  // Query battery-low flag, () → bool
        println!("warning: backup battery low - replace the coin cell");
    }

    // --- Hourly wake-up plus a 30 s heartbeat ---
    // Only the minute field is enabled, so the alarm matches at hh:00 every
    // hour. Timer B reloads automatically and has its own INT2 pin, so the
    // heartbeat keeps running independently of the hourly alarm.
    rtc.disable_clock_output().expect("disable_clock_output"); // Disable CLKOUT, () → ()
    rtc.set_alarm(Pcf8523Alarm { minute: Some(0), ..Default::default() })
        .expect("set_alarm");                           // Configure alarm, (Alarm { minute, hour, day, weekday }) → ()
    rtc.configure_timer_b(30, SourceClock::Hz1, 46.875, false)
        .expect("configure_timer_b");                   // Start Timer B, (value 0–255, source_clock, pulse_width_ms, pulsed) → ()
    rtc.enable_interrupt(PCF8523_SOURCE_ALARM | PCF8523_SOURCE_TIMER_B)
        .expect("enable_interrupt");                    // Enable sources, (source) → ()

    // --- Dispatch by source: log on the alarm, blink on the heartbeat ---
    // Rust drivers expose polling only; the caller owns the loop (or ISR).
    let mut alarms = 0;
    let mut led_on = false;
    while alarms < 3 {
        let status = rtc.poll_interrupt().expect("poll_interrupt"); // Poll & clear flags, () → u8
        if status & PCF8523_SOURCE_ALARM != 0 {
            let dt = rtc.get_datetime().expect("get_datetime"); // Read the calendar clock, () → DateTime
            println!("[hourly] {:04}-{:02}-{:02} {:02}:{:02}:{:02}",
                     dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);
            alarms += 1;
        }
        if status & PCF8523_SOURCE_TIMER_B != 0 {
            led_on = !led_on;
            println!("[heartbeat] LED {}", if led_on { "on" } else { "off" });
        }
        std::thread::sleep(std::time::Duration::from_millis(200));
    }

    // --- Leave the chip quiet on exit ---
    rtc.disable_interrupt(PCF8523_SOURCE_ALARM | PCF8523_SOURCE_TIMER_B)
        .expect("disable_interrupt");                   // Disable sources, (source) → ()
    rtc.disable_timer_b().expect("disable_timer_b");    // Stop Timer B, () → ()
}
