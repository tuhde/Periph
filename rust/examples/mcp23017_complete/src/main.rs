use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::Mcp23017Full;
use embedded_hal::digital::{OutputPin, InputPin};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x20);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip = Mcp23017Full::new(dev, addr).expect("init MCP23017"); // Create MCP23017 full driver, (i2c, addr=0x20) → Result

    let mut p0 = chip.pin(0);                                          // Get full pin proxy, (n) → ExPin
                                                                // GPA0 as output
    println!("GPA0 direction = output");

    p0.set_high().expect("set_high");                                 // Set high, () → Result<(), E>
    p0.set_low().expect("set_low");                                   // Set low, () → Result<(), E>

    let v = p0.is_set_high().expect("is_set_high");                   // Read shadow register, () → Result<bool, E>
                                                                // reads shadow, not the bus
    println!("GPA0 shadow high: {}", v);

    let level = p0.is_high().expect("is_high");                       // Read actual level, () → Result<bool, E>
                                                                // reads GPIOA over I²C
    println!("GPA0 actual level: {}", level);

    let porta = chip.read_port(0).expect("read_port");                // Read all 8 pins, (port=0) → Result<u8, E>
                                                                // PORTA = GPIOA register
    let portb = chip.read_port(1).expect("read_port");                // Read all 8 pins, (port=1) → Result<u8, E>
                                                                // PORTB = GPIOB register
    println!("PORTA=0x{:02X}  PORTB=0x{:02X}", porta, portb);

    chip.write_port(0, 0b00001111).expect("write_port");             // Write all 8 pins, (port, mask) → Result<(), E>
                                                                // GPA0–GPA3 as outputs, GPA4–GPA6 inputs, GPA7 output
    chip.write_port(1, 0b11110000).expect("write_port");             // Write all 8 pins, (port, mask) → Result<(), E>
                                                                // GPB0–GPB3 output low, GPB4–GPB7 output high

    chip.configure_pullup(1, 0b01111111).expect("configure_pullup");  // Enable pull-ups, (port=1, mask) → Result<(), E>
                                                                // GPB0–GPB6 pull-ups enabled (inputs)

    chip.configure_polarity(0, 0x00).expect("configure_polarity");   // Configure polarity, (port=0, mask) → Result<(), E>

    chip.configure_pullup(0, 0x55).expect("configure_pullup");       // Enable pull-ups, (port=0, mask) → Result<(), E>
                                                                // GPA0, GPA2, GPA4, GPA6 pull-ups

    let flags = chip.read_interrupt_flags(0).expect("read_interrupt_flags"); // Read interrupt flags, (port=0) → Result<u8, E>
                                                                // INTFA register; 1 = pin caused interrupt
    println!("INT flags PORTA: 0x{:02X}", flags);

    let changed = chip.clear_interrupt(0).expect("clear_interrupt");  // Read and clear interrupt, (port=0) → Result<u8, E>
                                                                // INTCAPA; also returns changed bitmask
    println!("changed on init: 0x{:02X}", changed);

    let p1 = chip.pin(1);                                            // Get full pin proxy, (n) → ExPin
                                                                // GPA1 as input
    let _ = chip.pin(15);                                            // Get full pin proxy, (n) → ExPin
                                                                // GPB7 as output (output-only on hardware)

    chip.write_port(0, 0x00).expect("write_port");                  // Write all 8 pins, (port=0, mask) → Result<(), E>
    chip.write_port(1, 0x00).expect("write_port");                  // Write all 8 pins, (port=1, mask) → Result<(), E>

    let porta2 = chip.read_port(0).expect("read_port");             // Read all 8 pins, (port=0) → Result<u8, E>
    let portb2 = chip.read_port(1).expect("read_port");             // Read all 8 pins, (port=1) → Result<u8, E>
    println!("PORTA=0x{:02X}  PORTB=0x{:02X}", porta2, portb2);

    println!("All API methods exercised.");
    sleep(Duration::from_millis(100));
}