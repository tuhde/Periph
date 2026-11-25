#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::Pca9539Full;
use embedded_hal::digital::{InputPin, OutputPin, StatefulOutputPin};

esp_app_desc!();

const ADDR: u8 = 0x74;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let chip = Pca9539Full::new(i2c, ADDR).expect("init PCA9539");       // Create PCA9539 driver, (i2c, addr=0x74) → Result

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

    loop { delay.delay_ms(1000); }
}
