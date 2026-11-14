use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::tof::{Vl6180xFull, VL6180X_I2C_ADDRESS, VL6180X_MODE_NEW_SAMPLE_READY, VL6180X_MODE_OUT_OF_WINDOW, VL6180X_SOURCE_RANGE};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut sensor = Vl6180xFull::new(dev, VL6180X_I2C_ADDRESS);      // Create VL6180X Full driver, (i2c, addr=0x29)
    sensor.init(&mut delay).expect("init VL6180X");                   // Run the init sequence, (delay) → ()
                                                                      // private settings only when fresh out of reset

    println!("{:?}", sensor.read_range(&mut delay));                  // Single-shot range, (delay) → u8 mm
                                                                      // Err(Range(code)) on a non-zero range error code
    println!("{:?}", sensor.read_range_status(&mut delay));           // Range and error code, (delay) → (u8 mm, u8 error)
                                                                      // never fails on a range error; 0 = valid
    println!("{:?}", sensor.read_als_raw(&mut delay));                // Single-shot ALS count, (delay) → u16
    println!("{:?}", sensor.read_lux(&mut delay));                    // Single-shot ALS in lux, (delay) → f32 lux
                                                                      // 0.32 lux/count at gain 1, 100 ms

    sensor.start_continuous_range(50).expect("start");                // Start continuous ranging, (period_ms) → ()
                                                                      // 10-2550 ms in 10 ms steps
    println!("{:?}", sensor.read_range_continuous(&mut delay));       // Wait for next sample, (delay) → u8 mm
    sensor.stop_continuous_range().expect("stop");                    // Stop continuous ranging, () → ()
    sensor.start_continuous_als(100).expect("start");                 // Start continuous ALS, (period_ms) → ()
    sensor.stop_continuous_als().expect("stop");                      // Stop continuous ALS, () → ()
    sensor.set_interleaved(false).expect("interleaved");              // Interleaved ALS + range, (on) → ()

    sensor.set_als_gain(6).expect("gain");                            // ALS gain code, (code) → ()
                                                                      // 0 = 20 ... 6 = 1, 7 = 40
    sensor.set_als_integration_time(100).expect("integration");       // ALS integration time, (ms) → ()
                                                                      // 1-512 ms
    sensor.set_als_scaler(1).expect("scaler");                        // ALS result scaler, (scaler) → ()
    sensor.set_range_thresholds(30, 80).expect("thresholds");         // Range thresholds, (low, high) → ()
    sensor.set_als_thresholds(100, 5000).expect("thresholds");        // ALS thresholds, (low, high) → ()
    sensor.set_max_convergence_time(49).expect("convergence");        // Max convergence time, (ms) → ()
    sensor.set_averaging_period(48).expect("averaging");              // Averaging period, (units) → ()
    sensor.set_range_offset(0).expect("offset");                      // Part-to-part offset, (mm) → ()
    println!("{:?}", sensor.get_range_offset());                      // Read the offset, () → i8 mm
    sensor.set_crosstalk_rate(0.0).expect("crosstalk");               // Crosstalk rate, (rate_mcps) → ()
    sensor.recalibrate_vhv(&mut delay).expect("recalibrate");         // Recalibrate VHV, (delay) → ()

    println!("model 0x{:02X} {:?}", sensor.model_id().unwrap(), sensor.revision());// Identification, () → u8 ; () → (u8, u8)
                                                                      // model ID always 0xB4
    println!("{:?}", sensor.read_history());                          // History buffer, () → [u16; 8]

    sensor.enable_interrupt(VL6180X_SOURCE_RANGE, VL6180X_MODE_OUT_OF_WINDOW).expect("irq");// Enable an interrupt, (source, mode) → ()
    println!("pending 0x{:02X}", sensor.poll_interrupt().unwrap());   // Read and clear, () → u8 SOURCE_* mask
                                                                      // polling only; no callback in Rust
    sensor.disable_interrupt(VL6180X_SOURCE_RANGE).expect("irq");     // Disable an interrupt, (source) → ()
    sensor.enable_interrupt(VL6180X_SOURCE_RANGE, VL6180X_MODE_NEW_SAMPLE_READY).expect("irq");// Restore the default mode, (source, mode) → ()
    // sensor.set_address(0x30)  // Change address (volatile), (address) → (); the driver follows
    let _ = sleep;
    let _ = Duration::from_millis(1);
}
