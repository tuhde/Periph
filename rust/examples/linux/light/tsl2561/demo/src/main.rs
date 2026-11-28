// Demo: a light meter with alarm.
// Prints the ID, then takes an auto-ranged lux reading every 2 s for 30 s with
// the selected gain, integration time and both channel counts. Then it sets an
// interrupt window of ±50 % around the current channel-0 count with persistence
// 2, reports poll_interrupt() while you cover the sensor, releases the
// interrupt and powers the chip down.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::{Tsl2561Full, TSL2561_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let mut delay = Delay;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Tsl2561Full::new(dev, TSL2561_I2C_ADDRESS);     // Create TSL2561 Full driver, (i2c, addr=0x39)
    sensor.init(&mut delay).expect("init");                          // Run initialization sequence, (delay) → ()
    println!("ID 0x{:02X}", sensor.read_id().expect("id"));          // Read the ID register, () → u8

    /* Light meter: auto-range picks the most sensitive setting that does not saturate. */
    for _ in 0..15 {
        let lux = sensor.auto_range(&mut delay).expect("auto");      // Auto-range and read lux, (delay) → f32 lux
        let (ch0, ch1) = sensor.read_raw().expect("raw");            // Read both channels, () → (u16, u16)
        println!("{:9.1} lx  gain {:2}x  {:3} ms  ch0 {:5}  ch1 {:5}",
                 lux, sensor.get_gain(), sensor.get_integration_time(), ch0, ch1);  // Current settings, () → u8 / u16
        sleep(Duration::from_secs(2));
    }

    /* Alarm: a window of ±50 % around the current channel-0 count. */
    let (ch0, _) = sensor.read_raw().expect("raw");
    let high = ((ch0 as u32 * 3) / 2).min(65535) as u16;
    sensor.set_thresholds(ch0 / 2, high).expect("thr");             // Set channel-0 window, (low, high) → ()
    sensor.configure_interrupt(1, 2).expect("int");                  // Level interrupt, 2 cycles outside, (mode, persist) → ()
    println!("window {:?} - cover the sensor now", sensor.get_thresholds().expect("thr"));  // Read the window, () → (u16, u16)
    for _ in 0..10 {
        println!("outside window: {}", sensor.poll_interrupt().expect("poll"));  // Check ch0 against the window, () → bool
        sleep(Duration::from_secs(1));
    }

    sensor.clear_interrupt().expect("clear");                        // Release the level interrupt, () → ()
    sensor.configure_interrupt(0, 0).expect("int");
    sensor.power_down().expect("down");                              // Power down, () → ()
    println!("powered: {}", sensor.is_powered().expect("powered"));  // Power state, () → bool
}
