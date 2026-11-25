use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::Pca9539Minimal;
use embedded_hal::digital::{InputPin, OutputPin};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x74);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip = Pca9539Minimal::new(dev, addr).expect("init PCA9539");    // Create PCA9539 driver, (i2c, addr=0x74) → Result

    let mut p0 = chip.pin(0);                                              // Get pin proxy, (n) → ExPin
    let mut p15 = chip.pin(15);                                            // Get pin proxy, (n) → ExPin
    p15.into_output().expect("into_output");                               // Make P17 an output, () → Result<(), E>

    loop {
        let port  = chip.read_port(1).expect("read_port");               // Read the 8 pins of port 1, (port) → Result<u8, E>
        let level = p0.is_high().expect("is_high");                        // Read actual level, () → Result<bool, E>
        if level { p15.set_high().expect("set_high"); }                     // Set high, () → Result<(), E>
        else     { p15.set_low().expect("set_low");   }                     // Drive low, () → Result<(), E>
        println!("PORT1=0x{:02X}  P0={}", port, level as u8);
        sleep(Duration::from_millis(200));
    }
}
