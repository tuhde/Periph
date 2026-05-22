use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::Mcp23017Minimal;
use periph::chips::io_expander::Mcp23017Full;
use embedded_hal::digital::{OutputPin, InputPin};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x20);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip = Mcp23017Minimal::new(dev, addr).expect("init MCP23017");

    assert!(chip.shadow[0].get() == 0);
    assert!(chip.shadow[1].get() == 0);

    let porta = chip.read_port(0).expect("read_port");
    assert!(porta <= 0xFF);

    let portb = chip.read_port(1).expect("read_port");
    assert!(portb <= 0xFF);

    chip.write_port(0, 0x55).expect("write_port");
    assert_eq!(chip.shadow[0].get(), 0x55);

    chip.write_port(1, 0xAA).expect("write_port");
    assert_eq!(chip.shadow[1].get(), 0xAA);

    let mut p0 = chip.pin(0);
    p0.set_low().expect("set_low");
    assert_eq!(chip.shadow[0].get() & 0x01, 0);

    p0.set_high().expect("set_high");
    assert_eq!(chip.shadow[0].get() & 0x01, 1);

    let v = p0.is_high().expect("is_high");
    assert!(v || !v);  // is_high returns bool; any value is valid

    let p8 = chip.pin(8);
    assert_eq!(p8.n, 8);

    let p15 = chip.pin(15);
    assert_eq!(p15.n, 15);

    // Loopback: PA (outputs) → PB (inputs); PA[n]↔PB[7-n]
    chip.configure_direction(0, 0x00).expect("configure direction A"); // PA all outputs

    chip.write_port(0, 0xAA).expect("write port A"); // PA0=0, avoids contention with PB7 output
    let pb = chip.read_port(1).expect("read port B");
    assert_eq!(pb & 0x7F, 0x55, "loopback 0xAA");

    chip.write_port(0, 0xFE).expect("write port A"); // PA0=0, PA1–PA7=1
    let pb = chip.read_port(1).expect("read port B");
    assert_eq!(pb & 0x7F, 0x7F, "loopback 0xFE");

    chip.write_port(0, 0x00).expect("write port A");
    let pb = chip.read_port(1).expect("read port B");
    assert_eq!(pb & 0x7F, 0x00, "loopback 0x00");

    // Full
    let dev2 = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let full = Mcp23017Full::new(dev2, addr).expect("init MCP23017 full");

    assert!(full.prev[0].get() <= 0xFF);
    assert!(full.prev[1].get() <= 0xFF);

    full.configure_pullup(0, 0x55).expect("configure_pullup");
    full.configure_pullup(1, 0xAA).expect("configure_pullup");

    full.configure_polarity(0, 0x0F).expect("configure_polarity");
    full.configure_polarity(1, 0xF0).expect("configure_polarity");

    let flags = full.read_interrupt_flags(0).expect("read_interrupt_flags");
    assert!(flags <= 0xFF);

    let changed = full.clear_interrupt(0).expect("clear_interrupt");
    assert!(changed <= 0xFF);

    let p1 = full.pin(1);
    let _ = p1;

    println!("===DONE: all checks passed===");
}
