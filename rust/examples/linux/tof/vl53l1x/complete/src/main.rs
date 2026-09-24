use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::tof::{
    Vl53l1xDistanceMode, Vl53l1xFull, VL53L1X_I2C_ADDRESS, VL53L1X_SOURCE_IN_WINDOW, VL53L1X_SOURCE_NEW_SAMPLE_READY,
};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Vl53l1xFull::new(dev, VL53L1X_I2C_ADDRESS, Delay).expect("init VL53L1X"); // Create VL53L1X Full driver, (i2c, addr=0x29, delay)
                                                                     // runs init: boot poll, ID check, ULD default config

    println!("model 0x{:02X}", sensor.model_id().unwrap());           // Read model ID, () → u8
                                                                     // IDENTIFICATION__MODEL_ID, always 0xEA
    println!("module 0x{:02X}", sensor.module_type().unwrap());       // Read module type, () → u8
                                                                     // IDENTIFICATION__MODULE_TYPE, always 0xCC
    println!("revision 0x{:02X}", sensor.revision_id().unwrap());     // Read revision ID, () → u8
                                                                     // mask revision, 0x10

    let d = sensor.distance().unwrap();                              // Measure distance, () → u16 mm
                                                                     // single shot; blocks for about one timing budget
    println!("distance {} mm, valid {}", d, sensor.range_valid());   // Check last measurement, () → bool
                                                                     // mapped range status == 0
    println!("range status {}", sensor.range_status());              // Read last range status, () → u8
                                                                     // 0 = valid, 2 = signal fail, 4 = out of bounds
    let m = sensor.read_measurement().unwrap();                      // Read result block, () → Vl53l1xMeasurement
                                                                     // distance, status, signal/ambient MCPS, SPAD count
    println!("signal {:.2} MCPS, ambient {:.2} MCPS, {:.1} SPADs",
             m.signal_rate_mcps, m.ambient_rate_mcps, m.effective_spad_count);

    println!("mode {:?}", sensor.distance_mode().unwrap());          // Read distance mode, () → Vl53l1xDistanceMode
                                                                     // from PHASECAL_CONFIG__TIMEOUT_MACROP
    sensor.set_distance_mode(Vl53l1xDistanceMode::Short).unwrap();   // Set distance mode, (mode) → ()
                                                                     // ~1.3 m, robust in sunlight; keeps the budget
    println!("budget {} us", sensor.timing_budget().unwrap());       // Read timing budget, () → u32 µs
                                                                     // decoded from the range timeout A register
    sensor.set_timing_budget(33000).unwrap();                        // Set timing budget, (budget_us µs) → ()
                                                                     // ULD table values 15000 (short only) … 500000
    println!("short mode {} mm", sensor.distance().unwrap());        // Measure distance, () → u16 mm
                                                                     // 33 ms single shot
    sensor.set_distance_mode(Vl53l1xDistanceMode::Long).unwrap();    // Set distance mode, (mode) → ()
                                                                     // back to up to 4 m in the dark
    sensor.set_timing_budget(100000).unwrap();                       // Set timing budget, (budget_us µs) → ()
                                                                     // default 100 ms

    sensor.set_inter_measurement(200).unwrap();                      // Set inter-measurement period, (period_ms ms) → ()
                                                                     // must be ≥ the timing budget
    println!("period {} ms", sensor.inter_measurement().unwrap());   // Read inter-measurement period, () → u32 ms
                                                                     // oscillator ticks scaled by the PLL calibration
    sensor.start_continuous(200).unwrap();                           // Start continuous ranging, (period_ms=0 ms) → ()
                                                                     // timed mode; 0 = as fast as the budget allows
    for _ in 0..5 {
        println!("continuous {} mm", sensor.read_continuous().unwrap()); // Read next continuous result, () → u16 mm
                                                                     // waits for data ready, then clears it
    }
    while !sensor.data_ready().unwrap() {                            // Check for a result, () → bool
        sleep(Duration::from_millis(10));                            // GPIO1 line asserted
    }
    println!("record {} mm", sensor.read_measurement().unwrap().distance_mm); // Read result block, () → Vl53l1xMeasurement
                                                                     // non-blocking; clears the interrupt
    sensor.stop_continuous().unwrap();                               // Stop continuous ranging, () → ()
                                                                     // does not wait for a running measurement
    sleep(Duration::from_millis(250));

    println!("signal limit {:.3} MCPS", sensor.signal_rate_limit().unwrap()); // Read signal-rate limit, () → f32 MCPS
                                                                     // 9.7 fixed point, default 1.0
    sensor.set_signal_rate_limit(0.5).unwrap();                      // Set signal-rate limit, (limit_mcps MCPS) → ()
                                                                     // lower = longer range, more noise
    sensor.set_signal_rate_limit(1.0).unwrap();                      // Set signal-rate limit, (limit_mcps MCPS) → ()
                                                                     // restore the default
    println!("sigma {} mm", sensor.sigma_threshold().unwrap());      // Read sigma threshold, () → u16 mm
                                                                     // 14.2 fixed point, default 90
    sensor.set_sigma_threshold(60).unwrap();                         // Set sigma threshold, (sigma_mm mm) → ()
                                                                     // stricter repeatability filter
    sensor.set_sigma_threshold(90).unwrap();                         // Set sigma threshold, (sigma_mm mm) → ()
                                                                     // restore the default

    let centre = sensor.optical_center().unwrap();                   // Read optical-centre SPAD, () → u8
                                                                     // factory NVM value for this part's lens
    println!("optical centre {}", centre);
    sensor.set_roi(8, 8).unwrap();                                   // Set ROI size, (width SPADs, height SPADs) → ()
                                                                     // 4–16 each; narrows the field of view
    sensor.set_roi_center(centre).unwrap();                          // Set ROI centre, (spad) → ()
                                                                     // align the narrow ROI with the lens
    println!("roi {:?} centre {}", sensor.roi().unwrap(), sensor.roi_center().unwrap()); // Read ROI size, () → (u8, u8); Read ROI centre, () → u8
                                                                     // (width, height) in SPADs
    sensor.set_roi(16, 16).unwrap();                                 // Set ROI size, (width SPADs, height SPADs) → ()
                                                                     // full array; re-centres on SPAD 199

    let original = sensor.offset().unwrap();                         // Read range offset, () → f32 mm
                                                                     // NVM factory value, 0.25 mm steps
    sensor.set_offset(original - 5.0).unwrap();                      // Set range offset, (offset_mm mm) → ()
                                                                     // volatile override, −1024.0 to 1023.75 mm
    println!("offset {:.2} mm", sensor.offset().unwrap());           // Read range offset, () → f32 mm
                                                                     // 13-bit two's complement × 0.25
    sensor.set_crosstalk_compensation(0.01).unwrap();                // Set crosstalk compensation, (rate_mcps MCPS) → ()
                                                                     // per-SPAD rate, 7.9 kcps register
    println!("crosstalk {:.4} MCPS", sensor.crosstalk_compensation().unwrap()); // Read crosstalk compensation, () → f32 MCPS
                                                                     // 0 = off
    println!("calibrated offset {:.2} mm", sensor.calibrate_offset(140).unwrap()); // Calibrate offset, (target_mm mm) → f32 mm
                                                                     // 50 samples against a target at 140 mm; applies it
    println!("calibrated crosstalk {:.4} MCPS", sensor.calibrate_crosstalk(600).unwrap()); // Calibrate crosstalk, (target_mm mm) → f32 MCPS
                                                                     // 50 samples against a target at 600 mm; applies it
    sensor.set_offset(original).unwrap();                            // Set range offset, (offset_mm mm) → ()
                                                                     // restore the factory value
    sensor.set_crosstalk_compensation(0.0).unwrap();                 // Set crosstalk compensation, (rate_mcps MCPS) → ()
                                                                     // compensation off

    sensor.recalibrate().unwrap();                                   // Run temperature update, () → ()
                                                                     // full VHV; after a > 8 °C change, not while ranging

    sensor.set_interrupt_thresholds(100, 800).unwrap();              // Set distance thresholds, (low_mm mm, high_mm mm) → ()
                                                                     // 1 mm resolution
    println!("thresholds {:?}", sensor.interrupt_thresholds().unwrap()); // Read distance thresholds, () → (u16 mm, u16 mm)
                                                                     // (low, high)
    sensor.enable_interrupt(VL53L1X_SOURCE_IN_WINDOW).unwrap();      // Select interrupt source, (source) → ()
                                                                     // fires while 100 mm ≤ range ≤ 800 mm
    sensor.disable_interrupt(VL53L1X_SOURCE_IN_WINDOW).unwrap();     // Disable interrupt source, (source) → ()
                                                                     // reverts to new-sample-ready (no disabled state)
    sensor.enable_interrupt(VL53L1X_SOURCE_NEW_SAMPLE_READY).unwrap(); // Select interrupt source, (source) → ()
                                                                     // the default data-ready source
    sensor.start_continuous(200).unwrap();                           // Start continuous ranging, (period_ms=0 ms) → ()
                                                                     // timed mode
    sleep(Duration::from_millis(300));
    println!("interrupt source {}", sensor.poll_interrupt().unwrap()); // Read and clear interrupt, () → u8
                                                                     // active SOURCE_* value, 0 = nothing pending
    sensor.stop_continuous().unwrap();                               // Stop continuous ranging, () → ()
                                                                     // back to software standby

    sensor.set_address(0x30).unwrap();                               // Change I²C address, (address) → ()
                                                                     // volatile; release the bus and rebuild the driver
    let (dev, delay) = sensor.release();                             // Release bus and delay, () → (I2C, D)
                                                                     // this driver instance is now unusable
    let mut moved = Vl53l1xFull::new(dev, 0x30, delay).expect("init at 0x30"); // Create VL53L1X Full driver, (i2c, addr=0x29, delay)
                                                                     // re-init at the new address is safe
    println!("at 0x30 {} mm", moved.distance().unwrap());            // Measure distance, () → u16 mm
                                                                     // same sensor, new address
    moved.set_address(VL53L1X_I2C_ADDRESS).unwrap();                 // Change I²C address, (address) → ()
                                                                     // back to the power-on 0x29
}
