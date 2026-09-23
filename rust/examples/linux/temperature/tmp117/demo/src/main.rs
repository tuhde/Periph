//! PT100-replacement cold-chain container thermometer: maximum averaging gives
//! the lowest-noise reading, and the ALERT output flags the cargo leaving the
//! -25 °C to 8 °C safe transport range; each event is reported with the
//! boundary that tripped. Set REFERENCE_C to a reference thermometer reading
//! to calibrate once and persist the offset to EEPROM. Rust has no callback
//! subscription, so the loop polls the alert flags — wire poll_interrupt()
//! into an ISR on the ALERT pin to do the same without polling.

use linux_embedded_hal::I2cdev;
use periph::chips::temperature::{
    Tmp117AlertMode, Tmp117AlertPinFunction, Tmp117AlertPolarity, Tmp117Full, Tmp117Mode,
    TMP117_I2C_ADDRESS, TMP117_SOURCE_HIGH, TMP117_SOURCE_LOW,
};
use std::thread::sleep;
use std::time::Duration;

const MAX_ALERTS: u32 = 10;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let reference_c: Option<f32> = std::env::var("REFERENCE_C").ok().and_then(|v| v.parse().ok());

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Tmp117Full::new(dev, TMP117_I2C_ADDRESS).expect("init TMP117"); // Create TMP117 Full driver, (i2c, addr=0x48)

    // --- Lowest-noise continuous conversion ---
    // 64-conversion averaging with a 1 s cycle gives the quietest result the
    // chip can deliver — a cold-chain log needs stability, not speed.
    sensor.configure(Tmp117Mode::Continuous, 64, 1.0).expect("configure"); // Configure conversion, (mode, averaging 0|8|32|64, cycle_seconds s) → ()

    // --- One-time calibration against a reference thermometer ---
    // The observed error is written to TEMP_OFFSET with the EEPROM unlocked, so
    // the correction survives power cycles. EEPROM endurance is limited — this
    // is a once-per-deployment step, not a loop.
    if let Some(reference) = reference_c {
        sleep(Duration::from_millis(1100));
        let offset = reference - sensor.read_temperature().expect("read_temperature") // Read temperature, () → f32 °C
            + sensor.get_temperature_offset().expect("get_temperature_offset");     // Read calibration offset, () → f32 °C
        sensor.unlock_eeprom().expect("unlock_eeprom");                              // Unlock EEPROM, () → ()
        sensor.set_temperature_offset(offset).expect("set_temperature_offset");      // Set calibration offset, (celsius °C) → ()
        while sensor.is_eeprom_busy().expect("is_eeprom_busy") {                     // Check EEPROM busy, () → bool
            sleep(Duration::from_millis(1));
        }
        sensor.lock_eeprom().expect("lock_eeprom");                                  // Lock EEPROM, () → ()
        println!("calibrated, offset {:.4} °C", offset);
    }

    // --- Program the safe transport range ---
    // Alert mode flags either side of the window independently; ALERT is
    // active-low open-drain, pulled up on the board.
    sensor.set_high_limit(8.0).expect("set_high_limit");   // Set THIGH_LIMIT, (celsius °C) → ()
    sensor.set_low_limit(-25.0).expect("set_low_limit");   // Set TLOW_LIMIT, (celsius °C) → ()
    sensor
        .configure_alert(Tmp117AlertMode::Alert, Tmp117AlertPolarity::ActiveLow, Tmp117AlertPinFunction::Alert)
        .expect("configure_alert"); // Configure ALERT, (mode, polarity, pin_function) → ()
    println!("monitoring, {:.2} °C now", sensor.read_temperature().expect("read_temperature")); // Read temperature, () → f32 °C

    // --- Report which boundary tripped ---
    // Reading the flags clears them in Alert mode, re-arming for the next
    // excursion.
    let mut alerts = 0;
    while alerts < MAX_ALERTS {
        let status = sensor.poll_interrupt().expect("poll_interrupt"); // Read alert flags, () → u8 mask
        if status != 0 {
            let t = sensor.read_temperature().expect("read_temperature"); // Read temperature, () → f32 °C
            if status & TMP117_SOURCE_HIGH != 0 {
                println!("{:.2} °C  too warm — cargo above 8 °C", t);
            } else if status & TMP117_SOURCE_LOW != 0 {
                println!("{:.2} °C  too cold — cargo below -25 °C", t);
            }
            alerts += 1;
        }
        sleep(Duration::from_secs(1));
    }
}
