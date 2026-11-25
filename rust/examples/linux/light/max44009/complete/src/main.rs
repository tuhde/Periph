// Complete example: exercise every method in the public API.
// Reads the illuminance and the raw exponent / mantissa, switches between
// automatic and manual ranging and between the measurement modes, programs
// the interrupt window and delay and reads the interrupt status.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::{MAX44009Full, MAX44009_I2C_ADDRESS};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = MAX44009Full::new(dev, MAX44009_I2C_ADDRESS); // Create MAX44009 Full driver, (i2c, addr=0x4A)
    sensor.init(&mut Delay).expect("init");                        // Run initialization sequence, (delay) → ()
    // writes the reset state, clears INT_STATUS, waits 0.9 s

    println!("lux {}", sensor.lux().expect("lux")); // Read illuminance, () → f32 lux
    // 2^E × M × 0.045; 188006.4 on overrange
    let (e, m, over) = sensor.read_raw().expect("read_raw"); // Read exponent and mantissa, () → (u8, u8, bool)
    // one consistent sample: LUX_HIGH, LUX_LOW, LUX_HIGH
    println!("E={} M={} overrange={}", e, m, over);
    println!("overrange {}", sensor.is_overrange()); // Query the last reading, () → bool
    // true if the last exponent field was 15
    println!("resolution {}", sensor.resolution()); // Lux per LSB, () → f32 lux
    // 2^E × 0.045 for the last reading

    sensor.set_continuous(true).expect("set_continuous"); // Select measurement mode, (continuous bool) → ()
    // true: a new measurement as soon as one finishes
    sensor.set_manual(100.0, false).expect("set_manual"); // Select manual range, (integration_ms, divide_by_8) → ()
    // nearest of 800/400/200/100/50/25/12.5/6.25 ms
    println!("{:?}", sensor.get_config().expect("get_config")); // Read CONFIG, () → MAX44009Config
    sensor.set_automatic().expect("set_automatic"); // Select automatic range, () → ()
    // the chip chooses the current divider and integration time
    sensor.set_continuous(false).expect("set_continuous"); // Back to one measurement per 800 ms, (continuous bool) → ()

    sensor.set_thresholds(50.0, 500.0).expect("set_thresholds"); // Set the interrupt window, (low_lux, high_lux) → Result
    // 4 mantissa MSBs: about 1/16 to 1/8 of the value
    println!("{:?}", sensor.get_thresholds().expect("get_thresholds")); // Read the window, () → (f32, f32)
    sensor.set_threshold_delay(2.0).expect("set_threshold_delay"); // Set the threshold delay, (seconds) → ()
    // 0–25.5 s in 0.1 s steps
    println!("delay {}", sensor.get_threshold_delay().expect("delay")); // Read the delay, () → f32 s

    sensor.enable_interrupt().expect("enable_interrupt"); // Enable the interrupt, () → ()
    // INT_ENABLE = 1
    println!("status {}", sensor.poll_interrupt().expect("poll_interrupt")); // Read and clear INT_STATUS, () → u8
    // bit 0 = SOURCE_THRESHOLD; call it from your INT falling-edge handler
    sensor.disable_interrupt().expect("disable_interrupt"); // Disable the interrupt, () → ()
    // INT_ENABLE = 0; also clears a pending status
}
