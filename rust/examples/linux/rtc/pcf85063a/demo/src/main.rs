//! Scheduling core of a battery-powered logger: reseeds the clock after a
//! power loss, counts boots in the free RAM byte, then wakes on an hourly
//! alarm to print a timestamp while a 30-second pulsed countdown timer
//! toggles an LED as a "still running" heartbeat.

use linux_embedded_hal::I2cdev;
use periph::chips::rtc::{
    MinuteInterrupt, OscillatorLoad, Pcf85063aAlarm, Pcf85063aDateTime, Pcf85063aFull,
    Pcf85063aOffsetMode, Pcf85063aSourceClock, PCF85063A_SOURCE_ALARM, PCF85063A_SOURCE_TIMER,
};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut rtc = Pcf85063aFull::new(dev, 0x51).expect("init PCF85063A"); // Create PCF85063A Full driver, (i2c, addr=0x51)

    // --- Detect a lost time reference and reseed if needed ---
    // A fresh chip, or one whose supply dropped out, reports the OS flag set:
    // its calendar cannot be trusted until reseeded. The crystal needs up to
    // 2 s to start, so wait until the flag stays clear.
    if rtc.oscillator_stopped().expect("oscillator_stopped") { // Query oscillator-stop flag, () → bool
        rtc.set_datetime(Pcf85063aDateTime {
            year: 2026, month: 1, day: 1, weekday: 4, hour: 0, minute: 0, second: 0,
        }).expect("set_datetime");                      // Set the calendar clock, (datetime, weekday 0=Sun) → ()
        println!("oscillator was stopped - reseeded from reference timestamp");
        while rtc.oscillator_stopped().expect("oscillator_stopped") { // Query oscillator-stop flag, () → bool
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
    }

    // --- Count boots in the free RAM byte ---
    // The RAM byte survives as long as VDD is present, so it works as a tiny
    // non-volatile counter next to the clock.
    let boots = rtc.read_ram().expect("read_ram").wrapping_add(1); // Read RAM byte, () → u8
    rtc.write_ram(boots).expect("write_ram");           // Write RAM byte, (value 0–255) → ()
    let now = rtc.get_datetime().expect("get_datetime"); // Read the calendar clock, () → DateTime
    let (offset, _mode): (i8, Pcf85063aOffsetMode) = rtc.get_offset().expect("get_offset"); // Read offset calibration, () → (i8, OffsetMode)
    println!("boot #{} {:04}-{:02}-{:02} {:02}:{:02}:{:02} offset={}",
             boots, now.year, now.month, now.day, now.hour, now.minute, now.second, offset);

    // --- Tune the board for low power ---
    // Match the crystal's load capacitance and switch the 32.768 kHz CLKOUT
    // off — it is enabled at power-on and costs current on a battery device.
    rtc.set_oscillator_load(OscillatorLoad::Pf12_5).expect("set_oscillator_load"); // Select crystal load, (load Pf7|Pf12_5) → ()
    rtc.disable_clock_output().expect("disable_clock_output"); // Disable CLKOUT, () → ()

    // --- Hourly wake-up plus a 30 s heartbeat ---
    // Only second and minute are enabled, so the alarm matches at hh:00:00
    // every hour. The pulsed timer reloads automatically; minute ticks share
    // the same TF flag and are reported as SOURCE_TIMER.
    rtc.set_alarm(Pcf85063aAlarm { second: Some(0), minute: Some(0), ..Default::default() })
        .expect("set_alarm");                           // Configure alarm, (Alarm { second, minute, hour, day, weekday }) → ()
    rtc.configure_timer(30, Pcf85063aSourceClock::Hz1, true)
        .expect("configure_timer");                     // Start countdown timer, (value 1–255, source_clock, pulsed) → ()
    rtc.set_minute_interrupt(MinuteInterrupt::Minute)
        .expect("set_minute_interrupt");                // Select minute interrupt, (mode Off|Minute|HalfMinute) → ()
    rtc.enable_interrupt(PCF85063A_SOURCE_ALARM | PCF85063A_SOURCE_TIMER)
        .expect("enable_interrupt");                    // Enable sources, (source) → ()

    // --- Dispatch by source: log on the alarm, blink on the heartbeat ---
    // Rust drivers expose polling only; the caller owns the loop (or ISR).
    let mut alarms = 0;
    let mut ticks = 0;
    let mut led_on = false;
    while alarms < 5 && ticks < 5 * 60 * 5 {
        let status = rtc.poll_interrupt().expect("poll_interrupt"); // Poll & clear flags, () → u8
        if status & PCF85063A_SOURCE_ALARM != 0 {
            let dt = rtc.get_datetime().expect("get_datetime"); // Read the calendar clock, () → DateTime
            println!("[hourly] {:04}-{:02}-{:02} {:02}:{:02}:{:02}",
                     dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);
            alarms += 1;
        }
        if status & PCF85063A_SOURCE_TIMER != 0 {
            led_on = !led_on;
            println!("[heartbeat] LED {}", if led_on { "on" } else { "off" });
        }
        std::thread::sleep(std::time::Duration::from_millis(200));
        ticks += 1;
    }

    // --- Leave the chip quiet on exit ---
    rtc.disable_interrupt(PCF85063A_SOURCE_ALARM | PCF85063A_SOURCE_TIMER)
        .expect("disable_interrupt");                   // Disable sources, (source) → ()
    rtc.disable_timer().expect("disable_timer");        // Stop timer, () → ()
    rtc.set_minute_interrupt(MinuteInterrupt::Off)
        .expect("set_minute_interrupt");                // Select minute interrupt, (mode Off|Minute|HalfMinute) → ()
}
