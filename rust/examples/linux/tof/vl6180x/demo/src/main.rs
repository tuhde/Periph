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

    // --- Identification ---
    println!("model 0x{:02X} revision {:?}", sensor.model_id().unwrap(), sensor.revision().unwrap());// Identification, () → u8 ; () → (u8, u8)

    // --- Single-shot ranging at 5 Hz for 10 s ---
    for _ in 0..50 {
        if let Ok(mm) = sensor.read_range(&mut delay) {               // Single-shot range, (delay) → u8 mm
            println!("{} mm", mm);
        }
        sleep(Duration::from_millis(200));
    }

    // --- Continuous ranging at 50 ms: move a hand towards the sensor ---
    sensor.start_continuous_range(50).expect("start");                // Start continuous ranging, (period_ms) → ()
    for _ in 0..40 {
        println!("{:?}", sensor.read_range_continuous(&mut delay));   // Wait for next sample, (delay) → u8 mm
    }
    sensor.stop_continuous_range().expect("stop");                    // Stop continuous ranging, () → ()
    sleep(Duration::from_millis(200));

    // --- Out-of-window interrupt for 30-80 mm ---
    sensor.set_range_thresholds(30, 80).expect("thresholds");         // Range thresholds, (low, high) → ()
    sensor.enable_interrupt(VL6180X_SOURCE_RANGE, VL6180X_MODE_OUT_OF_WINDOW).expect("irq");// Enable an interrupt, (source, mode) → ()
    sensor.start_continuous_range(50).expect("start");
    for _ in 0..50 {
        if sensor.poll_interrupt().unwrap() & VL6180X_SOURCE_RANGE != 0 {// Read and clear, () → u8 SOURCE_* mask
            println!("range out of window");
        }
        sleep(Duration::from_millis(100));
    }
    sensor.stop_continuous_range().expect("stop");
    sleep(Duration::from_millis(200));
    sensor.enable_interrupt(VL6180X_SOURCE_RANGE, VL6180X_MODE_NEW_SAMPLE_READY).expect("irq");

    // --- Light meter at gain 1 and 20 ---
    for code in [6u8, 0] {
        sensor.set_als_gain(code).expect("gain");                     // ALS gain code, (code) → ()
        println!("gain code {}: {:.2} lux", code, sensor.read_lux(&mut delay).unwrap());// Single-shot ALS in lux, (delay) → f32 lux
    }
    sensor.set_als_gain(6).expect("gain");

    // --- Interleaved ALS + range for 5 s ---
    sensor.set_interleaved(true).expect("interleaved");               // Interleaved ALS + range, (on) → ()
    sensor.start_continuous_als(100).expect("start");                 // Start continuous ALS, (period_ms) → ()
    sleep(Duration::from_secs(5));
    sensor.stop_continuous_als().expect("stop");                      // Stop continuous ALS, () → ()
    sensor.set_interleaved(false).expect("interleaved");

    // --- Calibration and diagnostics ---
    sensor.set_range_offset(0).expect("offset");                      // Part-to-part offset, (mm) → ()
    sensor.set_crosstalk_rate(0.0).expect("crosstalk");               // Crosstalk rate, (rate_mcps) → ()
    sensor.set_als_integration_time(100).expect("integration");       // ALS integration time, (ms) → ()
    sensor.set_als_scaler(1).expect("scaler");                        // ALS result scaler, (scaler) → ()
    sensor.recalibrate_vhv(&mut delay).expect("recalibrate");         // Recalibrate VHV, (delay) → ()
    println!("als raw {:?}, offset {:?}", sensor.read_als_raw(&mut delay), sensor.get_range_offset());// Single-shot ALS count, (delay) → u16 ; () → i8 mm
    println!("last range {:?}", sensor.read_range_status(&mut delay));// Range and error code, (delay) → (u8, u8)
    println!("history {:?}", sensor.read_history());                  // History buffer, () → [u16; 8]

    // --- Change the device address and back (volatile) ---
    sensor.set_address(0x30).expect("address");                       // Change address, (address) → (); the driver follows the new address
    println!("on 0x30: model 0x{:02X}", sensor.model_id().unwrap());
    sensor.set_address(VL6180X_I2C_ADDRESS).expect("address");
}
