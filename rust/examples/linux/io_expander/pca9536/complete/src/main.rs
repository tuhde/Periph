use linux_embedded_hal::I2cdev;
use periph::chips::io_expander::Pca9536Full;
use embedded_hal::digital::{InputPin, OutputPin, StatefulOutputPin};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x41);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let chip = Pca9536Full::new(dev, addr).expect("init PCA9536");       // Create PCA9536 driver, (i2c, addr=0x41) → Result

    let mut p0 = chip.pin(0);                                              // Get pin proxy, (n) → ExPin
                                                                           // P0; all four pins are bidirectional
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

    chip.write_port(0x05).expect("write_port");                            // Write the output latch, (mask) → Result<(), E>
                                                                           // whole-port OUTPUT write
    let port = chip.read_port().expect("read_port");                       // Read all 4 pins, () → Result<u8, E>
                                                                           // INPUT register
    println!("PORT=0x{:X}", port);

    chip.configure_polarity(0x00).expect("configure_polarity");            // Input polarity, (mask) → Result<(), E>
                                                                           // 1 bits invert the INPUT read
    chip.set_direction(0x0C).expect("set_direction");                      // Whole-port direction, (mask) → Result<(), E>
                                                                           // P0–P1 outputs, P2–P3 inputs; OUTPUT is written first
    let dir = chip.get_direction();                                        // Read the direction shadow, () → u8
                                                                           // 1 = input, 0 = output
    println!("CONFIG=0x{:X}", dir);
    let changed = chip.poll_changes().expect("poll_changes");          // Read INPUT, get changed mask, () → Result<u8, E>
                                                                           // XOR with the last INPUT value
    println!("changed=0x{:X}", changed);

    let config = chip.read_register(0x03).expect("read_register");         // Read raw register, (reg) → Result<u8, E>
                                                                           // escape hatch for debugging
    println!("CONFIG=0x{:X}", config);
    chip.write_register(0x02, 0x04).expect("write_register");              // Write raw register, (reg, value) → Result<bool, E>
                                                                           // POLARITY: invert P2
    chip.reset().expect("reset");                                          // Restore reset values, () → Result<(), E>
                                                                           // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF
}
