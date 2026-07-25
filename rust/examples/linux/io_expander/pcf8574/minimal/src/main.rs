use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::Pcf8574Minimal;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x20);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip = Pcf8574Minimal::new(dev, addr).expect("init PCF8574"); // Create PCF8574 driver, (i2c, addr=0x20) → Result

    let mut p0 = chip.pin(0);                                          // Get pin proxy, (n) → ExPin
    let mut p4 = chip.pin(4);                                          // Get pin proxy, (n) → ExPin

    use embedded_hal::digital::{OutputPin, InputPin};
    p0.set_low().expect("set_low");                                    // Drive low, () → Result<(), E>

    loop {
        let port = chip.read_port().expect("read_port");               // Read all 8 pins, () → Result<u8, E>
        let btn4 = p4.is_high().expect("is_high");                     // Read actual level, () → Result<bool, E>
        if btn4 { p0.set_high().expect("set_high"); }                  // Set high (quasi-input), () → Result<(), E>
        else    { p0.set_low().expect("set_low");   }                  // Drive low, () → Result<(), E>
        println!("port=0x{:02X}  P4={}", port, btn4 as u8);
        sleep(Duration::from_millis(200));
    }
}
