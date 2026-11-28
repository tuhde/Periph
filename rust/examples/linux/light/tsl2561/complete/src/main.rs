// Complete example: exercise every method in the public API.
// Reads lux and the raw channel counts, changes gain, integration time and the
// package coefficients, auto-ranges, runs one host-timed manual integration,
// programs the interrupt window, then powers down.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::{tsl2561_calculate_lux, Tsl2561Full, Tsl2561Package, TSL2561_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let mut delay = Delay;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Tsl2561Full::new(dev, TSL2561_I2C_ADDRESS);     // Create TSL2561 Full driver, (i2c, addr=0x39)
    sensor.init(&mut delay).expect("init");                          // Run initialization sequence, (delay) → ()
    // CONTROL ← 0x03 and read back, TIMING ← 0x02 (1×, 402 ms), waits 450 ms

    println!("id 0x{:02X}", sensor.read_id().expect("id"));          // Read the ID register, () → u8
    // bits 7:4 = 1 for the TSL2561, bits 3:0 revision
    println!("{:?}", sensor.read_lux());                             // Read illuminance, () → Result<f32 lux>
    // Err(Saturated { ch0, ch1 }) on a saturated channel
    let (ch0, ch1) = sensor.read_raw().expect("raw");                // Read both channels, () → (u16 ch0, u16 ch1)
    // one block read: ch0 visible+IR, ch1 IR
    println!("{:?}", sensor.read_lux_raw());                         // Lux plus counts, () → Result<(f32, u16, u16)>
    println!("{:.1}", tsl2561_calculate_lux(ch0, ch1, 1, 402, Tsl2561Package::T));  // Convert counts, (ch0, ch1, gain, integration_ms, package) → f32 lux

    sensor.set_gain(16).expect("gain");                              // Set ADC gain, (gain 1|16) → ()
    // 16× for dim light; saturates indoors at ~1–2 klx
    sensor.set_integration_time(101).expect("integ");                // Set integration time, (ms 14|101|402) → ()
    // the conversion in progress keeps the old setting
    println!("gain {} integration {} ms", sensor.get_gain(), sensor.get_integration_time());  // Current settings, () → u8, () → u16 ms
    sleep(Duration::from_millis(250));
    sensor.set_package(Tsl2561Package::CS);                          // Select lux coefficients, (package) → ()
    // T, FN and CL share one set; CS has its own
    println!("{:?}", sensor.read_lux());
    sensor.set_package(Tsl2561Package::T);
    println!("{:?}", sensor.auto_range(&mut delay));                 // Auto-range and read lux, (delay) → Result<f32 lux>
    // 16×/402 ms … 1×/13.7 ms until no saturation and ch0 > 100

    sensor.start_manual().expect("start");                           // Start a host-timed integration, () → ()
    // INTEG = 11, MANUAL = 1
    sleep(Duration::from_millis(200));
    sensor.stop_manual().expect("stop");                             // Stop the integration, () → ()
    // INTEG = 11, MANUAL = 0; counts are ready
    println!("{:?}", sensor.read_raw());
    sensor.set_integration_time(402).expect("integ");
    sensor.set_gain(1).expect("gain");

    sensor.set_thresholds(100, 20000).expect("thr");                 // Set channel-0 window, (low, high counts) → ()
    // word writes of 0x2–0x3 and 0x4–0x5
    println!("{:?}", sensor.get_thresholds());                       // Read the window, () → (u16 low, u16 high)
    sensor.configure_interrupt(1, 2).expect("int");                  // Interrupt mode, (mode 0|1|2, persist 0–15) → ()
    // level interrupt after 2 cycles outside the window
    println!("outside {:?}", sensor.poll_interrupt());               // Check ch0 against the window, () → bool
    // does not clear the chip's interrupt
    sensor.clear_interrupt().expect("clear");                        // Release a level interrupt, () → ()
    // command byte 0xC0
    sensor.configure_interrupt(0, 0).expect("int");

    println!("powered {:?}", sensor.is_powered());                   // Power state, () → bool
    // CONTROL reads 0x03 when powered
    sensor.power_down().expect("down");                              // Power down, () → ()
    // CONTROL = 0x00; registers keep their values
    sensor.power_up().expect("up");                                  // Power up, () → ()
    // CONTROL = 0x03; integration restarts
}
