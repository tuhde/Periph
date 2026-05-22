use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::{Pcf8575Minimal, Pcf8575Full};
use embedded_hal::digital::{OutputPin, InputPin, StatefulOutputPin};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x20);

    // --- Pcf8575Minimal ---
    let dev1  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip1 = Pcf8575Minimal::new(dev1, addr).expect("init");        // Create PCF8575 minimal driver, (i2c, addr) → Result
                                                                        // initialises all pins as inputs; shadow = [0xFF, 0xFF]

    let mut p0 = chip1.pin(0);                                         // Get pin proxy, (n=0) → ExPin
    p0.set_high().expect("set_high");                                  // Set high (quasi-input), () → Result<(), E>
    p0.set_low().expect("set_low");                                    // Drive low, () → Result<(), E>
    let high = p0.is_high().expect("is_high");                         // Read actual level, () → Result<bool, E>
    let low  = p0.is_low().expect("is_low");                           // Read actual level, () → Result<bool, E>
    println!("P0 high={} low={}", high, low);

    let set_high = p0.is_set_high().expect("is_set_high");             // Read shadow (no bus), () → Result<bool, E>
    let set_low  = p0.is_set_low().expect("is_set_low");               // Read shadow (no bus), () → Result<bool, E>
    println!("shadow: set_high={} set_low={}", set_high, set_low);

    let port0 = chip1.read_port(0).expect("read_port port0");          // Read Port 0, (port=0) → Result<u8, E>
    let port1 = chip1.read_port(1).expect("read_port port1");          // Read Port 1, (port=1) → Result<u8, E>
    println!("P0=0x{:02X}  P1=0x{:02X}", port0, port1);

    chip1.write_port(0, 0b00001111).expect("write_port 0");            // Write Port 0, (port=0, mask) → Result<(), E>
    chip1.write_port(1, 0b00001111).expect("write_port 1");            // Write Port 1, (port=1, mask) → Result<(), E>

    let mut p8 = chip1.pin(8);                                         // Get pin proxy, (n=8) → ExPin
    let btn = p8.is_high().expect("is_high");                          // Read actual level, () → Result<bool, E>
    println!("P10={}", btn as u8);

    // --- Pcf8575Full ---
    let dev2  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip2 = Pcf8575Full::new(dev2, addr).expect("init full");     // Create PCF8575 full driver, (i2c, addr) → Result

    let changed = chip2.clear_interrupt().expect("clear_interrupt");  // Read port; return 16-bit changed bitmask, () → Result<u16, E>
    println!("changed on init=0x{:04X}", changed);

    let port2_0 = chip2.read_port(0).expect("read_port 2 port0");      // Read Port 0, (port=0) → Result<u8, E>
    let port2_1 = chip2.read_port(1).expect("read_port 2 port1");      // Read Port 1, (port=1) → Result<u8, E>
    println!("port2 P0=0x{:02X}  P1=0x{:02X}", port2_0, port2_1);
}