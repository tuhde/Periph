// Minimal example: take and release the downstream I²C bus.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::other::{PCA9541Minimal, PCA9541_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut selector = PCA9541Minimal::new(dev, PCA9541_I2C_ADDRESS); // Create PCA9541 driver, (i2c, addr=0x70)
    selector.init().expect("init");                                 // Run initialization sequence, () → ()

    println!("has control {}", selector.has_control().expect("has_control")); // Report control, () → bool
    println!("acquire {}", selector.acquire(&mut Delay, false, 20).expect("acquire")); // Take the downstream bus, (delay, bus_init, timeout_ms) → bool
    sleep(Duration::from_secs(1));
    selector.release().expect("release"); // Give the bus to the other master, () → ()
    println!("has control {}", selector.has_control().expect("has_control"));
}
