use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::Pca9539Full;
use embedded_hal::digital::{InputPin, OutputPin, StatefulOutputPin};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x74);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip = Pca9539Full::new(dev, addr).expect("init PCA9539");       // Create PCA9539 driver, (i2c, addr=0x74) → Result

    let mut p0 = chip.pin(0);                                              // Get pin proxy, (n) → ExPin
                                                                           // P00; all sixteen pins are bidirectional
    p0.into_output().expect("into_output");                                // Make it an output, () → Result<(), E>
                                                                           // latches OUTPUT, then clears the CONFIG bit
    p0.set_low().expect("set_low");                                        // Drive low, () → Result<(), E>
                                                                           // clears the OUTPUT bit via the shadow
    let set = p0.is_set_high().expect("is_set_high");                      // Read the latch, () → Result<bool, E>
                                                                           // from the shadow register, no bus transaction
    println!("P0 latch: {}", set);
    let level = p0.is_high().expect("is_high");                            // Read actual level, () → Result<bool, E>
                                                                           // reads INPUT
    println!("P0 level: {}", level);

    chip.write_port(0, 0x05).expect("write_port");                         // Write port 0 output latch, (port, mask) → Result<(), E>
                                                                           // single-port OUTPUT write, updates the shadow
    chip.write_all(0xA505).expect("write_all");                             // Write both output latches, (value) → Result<(), E>
                                                                            // one two-byte write at OUTPUT0 (pair toggling)
    let port = chip.read_port(1).expect("read_port");                    // Read port 1 pins, (port) → Result<u8, E>
                                                                           // INPUT1 register
    println!("PORT1=0x{:02X}", port);
    let all = chip.read_all().expect("read_all");                          // Read all 16 pins, () → Result<u16, E>
                                                                           // one two-byte read at INPUT0
    println!("ALL=0x{:04X}", all);

    chip.configure_polarity(0x0000).expect("configure_polarity");            // Input polarity, (mask) → Result<(), E>
                                                                           // 1 bits invert the INPUT read
    chip.set_direction(0xFF00).expect("set_direction");                    // Whole-device direction, (mask) → Result<(), E>
                                                                           // port 0 outputs, port 1 inputs; OUTPUT is written first
    let dir = chip.get_direction();                                        // Read the direction shadow, () → u16
                                                                           // 1 = input, 0 = output
    println!("CONFIG=0x{:04X}", dir);
    let changed = chip.poll_interrupt().expect("poll_interrupt");          // Read INPUT0/1, get changed mask, () → Result<u16, E>
                                                                           // XOR with the last INPUT value
    println!("changed=0x{:04X}", changed);

    let config = chip.read_register(0x07).expect("read_register");         // Read raw register, (reg) → Result<u8, E>
                                                                           // escape hatch for debugging
    println!("CONFIG1=0x{:02X}", config);
    chip.write_register(0x05, 0x10).expect("write_register");              // Write raw register, (reg, value) → Result<bool, E>
                                                                           // POLARITY1: invert P14
    chip.reset().expect("reset");                                          // Restore reset values, () → Result<(), E>
                                                                           // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF (both ports)
}
