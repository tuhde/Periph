use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::{Pcf8574Minimal, Pcf8574Full};
use embedded_hal::digital::{OutputPin, InputPin, StatefulOutputPin};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x20);

    // --- Pcf8574Minimal ---
    let dev1  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip1 = Pcf8574Minimal::new(dev1, addr).expect("init");        // Create PCF8574 minimal driver, (i2c, addr) → Result
                                                                       // initialises all pins as inputs; shadow = 0xFF

    let mut p0 = chip1.pin(0);                                         // Get pin proxy, (n) → ExPin
                                                                       // holds shared ref to chip; no bus transaction
    p0.set_high().expect("set_high");                                  // Set high (quasi-input), () → Result<(), E>
                                                                       // shadow |= (1 << 0); writes shadow byte to bus
    p0.set_low().expect("set_low");                                    // Drive low, () → Result<(), E>
                                                                       // shadow &= !(1 << 0); strong pull-down, ≤25 mA
    let high = p0.is_high().expect("is_high");                         // Read actual level, () → Result<bool, E>
                                                                       // reads bus byte; returns (byte >> n) & 1 == 1
    let low  = p0.is_low().expect("is_low");                           // Read actual level, () → Result<bool, E>
                                                                       // equivalent to !is_high()
    println!("P0 high={} low={}", high, low);

    let set_high = p0.is_set_high().expect("is_set_high");             // Read shadow (no bus), () → Result<bool, E>
                                                                       // reads shadow register; no I²C transaction
    let set_low  = p0.is_set_low().expect("is_set_low");               // Read shadow (no bus), () → Result<bool, E>
    println!("shadow: set_high={} set_low={}", set_high, set_low);

    let port = chip1.read_port().expect("read_port");                  // Read all 8 pins, () → Result<u8, E>
                                                                       // bit n = actual level of pin Pn
    println!("port=0x{:02X}", port);

    chip1.write_port(0b00001111).expect("write_port");                 // Write all 8 pins, (mask) → Result<(), E>
                                                                       // P0–P3 → output low; P4–P7 → input mode

    let mut p4 = chip1.pin(4);                                         // Get pin proxy, (n) → ExPin
    let btn = p4.is_high().expect("is_high");                          // Read actual level, () → Result<bool, E>
                                                                       // 1 if P4 floating high; 0 if button pressed
    println!("P4={}", btn as u8);

    // --- Pcf8574Full ---
    let dev2  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip2 = Pcf8574Full::new(dev2, addr).expect("init full");      // Create PCF8574 full driver, (i2c, addr) → Result
                                                                       // stores initial port byte for interrupt comparison

    let changed = chip2.clear_interrupt().expect("clear_interrupt");   // Read port; return changed bitmask, () → Result<u8, E>
                                                                       // XOR of current vs previous read; clears INT line
    println!("changed on init=0x{:02X}", changed);

    let port2 = chip2.read_port().expect("read_port");                 // Read all 8 pins, () → Result<u8, E>
    chip2.write_port(0xFF).expect("write_port");                       // Write all 8 pins, (mask) → Result<(), E>
    println!("port2=0x{:02X}", port2);
}
