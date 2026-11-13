// Minimal example: read the ambient illuminance once a second.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::{MAX44009Minimal, MAX44009_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = MAX44009Minimal::new(dev, MAX44009_I2C_ADDRESS); // Create MAX44009 driver, (i2c, addr=0x4A)
    sensor.init(&mut Delay).expect("init");                          // Run initialization sequence, (delay) → ()

    for _ in 0..10 {
        println!("{:.2} lx", sensor.lux().expect("lux")); // Read illuminance, () → f32 lux
        sleep(Duration::from_secs(1));
    }
}
