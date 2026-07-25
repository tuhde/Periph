use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::Pcf8575Minimal;
use embedded_hal::digital::OutputPin;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x20);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip = Pcf8575Minimal::new(dev, addr).expect("init PCF8575"); // Create PCF8575 driver, (i2c, addr=0x20) → Result

    let mut p0 = chip.pin(0);                                          // Get pin proxy, (n=0) → ExPin
    let mut p8 = chip.pin(8);                                          // Get pin proxy, (n=8) → ExPin

    p0.set_low().expect("set_low");                                   // Drive low, () → Result<(), E>

    loop {
        let port0 = chip.read_port(0).expect("read_port port0");       // Read Port 0, (port=0) → Result<u8, E>
        let port1 = chip.read_port(1).expect("read_port port1");       // Read Port 1, (port=1) → Result<u8, E>
        if (port1 & 0x01) != 0 { p0.set_high().expect("set_high"); }  // Set high (quasi-input), () → Result<(), E>
        else                  { p0.set_low().expect("set_low");  }    // Drive low, () → Result<(), E>
        println!("P0=0x{:02X}  P1=0x{:02X}", port0, port1);
        sleep(Duration::from_millis(200));
    }
}