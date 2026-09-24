use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::tof::{
    Vl53l0xFull, Vl53l0xProfile, Vl53l0xVcselPeriodType, VL53L0X_I2C_ADDRESS, VL53L0X_SOURCE_NEW_SAMPLE_READY,
    VL53L0X_SOURCE_OUT_OF_WINDOW,
};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Vl53l0xFull::new(dev, VL53L0X_I2C_ADDRESS, Delay).expect("init VL53L0X"); // Create VL53L0X Full driver, (i2c, addr=0x29, delay)
                                                                     // runs init: ID check, tuning, SPADs, VHV + phase calibration

    println!("model 0x{:02X}", sensor.model_id().unwrap());           // Read model ID, () → u8
                                                                     // IDENTIFICATION_MODEL_ID, always 0xEE
    println!("revision 0x{:02X}", sensor.revision_id().unwrap());     // Read revision ID, () → u8
                                                                     // IDENTIFICATION_REVISION_ID, 0x10 on current silicon

    let d = sensor.distance().unwrap();                              // Measure distance, () → u16 mm
                                                                     // single shot; blocks for about one timing budget
    println!("distance {} mm, valid {}", d, sensor.range_valid());   // Check last measurement, () → bool
                                                                     // device range status == 11 (range complete)
    println!("range status {}", sensor.range_status());              // Read last range status, () → u8 0–15
                                                                     // 11 = valid, 4 = no target
    let m = sensor.read_measurement().unwrap();                      // Read result block, () → Vl53l0xMeasurement
                                                                     // distance, status, signal/ambient MCPS, SPAD count
    println!("signal {:.2} MCPS, ambient {:.2} MCPS, {:.1} SPADs",
             m.signal_rate_mcps, m.ambient_rate_mcps, m.effective_spad_count);

    sensor.start_continuous(0).unwrap();                             // Start continuous ranging, (period_ms=0 ms) → ()
                                                                     // 0 = back-to-back measurements
    for _ in 0..5 {
        println!("continuous {} mm", sensor.read_continuous().unwrap()); // Read next continuous result, () → u16 mm
                                                                     // waits for a fresh data-ready, then clears it
    }
    sensor.stop_continuous().unwrap();                               // Stop continuous ranging, () → ()
                                                                     // does not wait for a running measurement

    sensor.start_continuous(100).unwrap();                           // Start continuous ranging, (period_ms=0 ms) → ()
                                                                     // timed mode: one measurement every 100 ms
    while !sensor.data_ready().unwrap() {                            // Check for a result, () → bool
        sleep(Duration::from_millis(10));                            // RESULT_INTERRUPT_STATUS bits 2:0 non-zero
    }
    println!("timed {} mm", sensor.read_measurement().unwrap().distance_mm); // Read result block, () → Vl53l0xMeasurement
                                                                     // non-blocking; clears the interrupt
    sensor.stop_continuous().unwrap();                               // Stop continuous ranging, () → ()
                                                                     // back to software standby

    println!("budget {} us", sensor.timing_budget().unwrap());       // Read timing budget, () → u32 µs
                                                                     // computed from the sequence-step timeouts
    sensor.set_timing_budget(50000).unwrap();                        // Set timing budget, (budget_us µs) → ()
                                                                     // longer budget = lower noise, ≥ 20000 µs
    println!("signal limit {:.3} MCPS", sensor.signal_rate_limit().unwrap()); // Read signal-rate limit, () → f32 MCPS
                                                                     // 9.7 fixed point
    sensor.set_signal_rate_limit(0.1).unwrap();                      // Set signal-rate limit, (limit_mcps MCPS) → ()
                                                                     // lower = longer range, more noise
    sensor.set_vcsel_pulse_period(Vl53l0xVcselPeriodType::PreRange, 18).unwrap();   // Set VCSEL period, (period_type, pclks) → ()
                                                                     // pre-range 12/14/16/18; redoes phase calibration
    sensor.set_vcsel_pulse_period(Vl53l0xVcselPeriodType::FinalRange, 14).unwrap(); // Set VCSEL period, (period_type, pclks) → ()
                                                                     // final-range 8/10/12/14
    println!("vcsel {}/{} PCLKs",
             sensor.vcsel_pulse_period(Vl53l0xVcselPeriodType::PreRange).unwrap(),    // Read VCSEL period, (period_type) → u8 PCLKs
             sensor.vcsel_pulse_period(Vl53l0xVcselPeriodType::FinalRange).unwrap()); // (reg + 1) × 2
    sensor.set_profile(Vl53l0xProfile::Default).unwrap();            // Apply ranging profile, (profile) → ()
                                                                     // 0.25 MCPS, 14/10 PCLKs, 33 ms

    let original = sensor.offset().unwrap();                         // Read range offset, () → f32 mm
                                                                     // NVM factory value, 0.25 mm steps
    sensor.set_offset(original - 5.0).unwrap();                      // Set range offset, (offset_mm mm) → ()
                                                                     // volatile override, −512.0 to 511.75 mm
    println!("offset {:.2} mm", sensor.offset().unwrap());           // Read range offset, () → f32 mm
                                                                     // 12-bit two's complement × 0.25
    sensor.set_offset(original).unwrap();                            // Set range offset, (offset_mm mm) → ()
                                                                     // restore the factory value
    sensor.set_crosstalk_compensation(0.0).unwrap();                 // Set crosstalk compensation, (rate_mcps MCPS) → ()
                                                                     // 0 = compensation off

    sensor.recalibrate().unwrap();                                   // Rerun reference calibration, () → ()
                                                                     // VHV + phase; needed after a > 8 °C change

    sensor.set_interrupt_thresholds(100, 800).unwrap();              // Set distance thresholds, (low_mm mm, high_mm mm) → ()
                                                                     // 2 mm resolution
    println!("thresholds {:?}", sensor.interrupt_thresholds().unwrap()); // Read distance thresholds, () → (u16 mm, u16 mm)
                                                                     // (low, high)
    sensor.enable_interrupt(VL53L0X_SOURCE_OUT_OF_WINDOW).unwrap();  // Select interrupt source, (source) → ()
                                                                     // replaces the active source (mutually exclusive)
    sensor.disable_interrupt(VL53L0X_SOURCE_OUT_OF_WINDOW).unwrap(); // Disable interrupt source, (source) → ()
                                                                     // only if it is the active one
    sensor.enable_interrupt(VL53L0X_SOURCE_NEW_SAMPLE_READY).unwrap(); // Select interrupt source, (source) → ()
                                                                     // back to the default data-ready source
    sensor.start_continuous(200).unwrap();                           // Start continuous ranging, (period_ms=0 ms) → ()
                                                                     // timed mode
    sleep(Duration::from_millis(300));
    println!("interrupt source {}", sensor.poll_interrupt().unwrap()); // Read and clear status, () → u8
                                                                     // SOURCE_* value that fired, 0 = nothing pending
    sensor.stop_continuous().unwrap();                               // Stop continuous ranging, () → ()
                                                                     // back to software standby

    sensor.set_address(0x30).unwrap();                               // Change I²C address, (address) → ()
                                                                     // volatile; release the bus and rebuild the driver
    let (dev, delay) = sensor.release();                             // Release bus and delay, () → (I2C, D)
                                                                     // this driver instance is now unusable
    let mut moved = Vl53l0xFull::new(dev, 0x30, delay).expect("init at 0x30"); // Create VL53L0X Full driver, (i2c, addr=0x29, delay)
                                                                     // re-init at the new address is safe
    println!("at 0x30 {} mm", moved.distance().unwrap());            // Measure distance, () → u16 mm
                                                                     // same sensor, new address
    moved.set_address(VL53L0X_I2C_ADDRESS).unwrap();                 // Change I²C address, (address) → ()
                                                                     // back to the power-on 0x29
}
