#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::Mcp23008Full;
use embedded_hal::digital::{InputPin, OutputPin, StatefulOutputPin};

esp_app_desc!();

const ADDR: u8 = 0x20;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let chip = Mcp23008Full::new(i2c, ADDR).expect("init MCP23008");   // Create MCP23008 driver, (i2c, ADDR=0x20) → Result
                                                                       // clears OLAT; all pins start as inputs

    let mut p0 = chip.pin(0);                                          // Get pin proxy, (n) → ExPin
                                                                       // GP0; all eight pins are bidirectional
    p0.into_output().expect("into_output");                            // Make it an output, () → Result<(), E>
                                                                       // latches OLAT, then clears the IODIR bit
    p0.set_high().expect("set_high");                                  // Set high, () → Result<(), E>
                                                                       // one OLAT write via the shadow
    let set = p0.is_set_high().expect("is_set_high");                  // Read the latch, () → Result<bool, E>
                                                                       // from the shadow register, no bus transaction
    println!("GP0 latch: {}", set);
    let level = p0.is_high().expect("is_high");                        // Read actual level, () → Result<bool, E>
                                                                       // reads GPIO
    println!("GP0 level: {}", level);

    chip.write_port(0x05).expect("write_port");                        // Write the output latch, (mask) → Result<(), E>
                                                                       // whole-port OLAT write
    let port = chip.read_port().expect("read_port");                   // Read all 8 pins, () → Result<u8, E>
                                                                       // GPIO register
    println!("GPIO=0x{:02X}", port);

    chip.configure_pullup(0xF0).expect("configure_pullup");            // Pull-ups on GP4–GP7, (mask) → Result<(), E>
                                                                       // writes GPPU; only effective on inputs
    chip.configure_polarity(0x00).expect("configure_polarity");        // Input polarity, (mask) → Result<(), E>
                                                                       // 1 bits invert the GPIO read
    chip.set_default_value(0x00).expect("set_default_value");          // Set DEFVAL, (mask) → Result<(), E>
                                                                       // reference for default-compare interrupts
    chip.set_interrupt_control(0x00).expect("set_interrupt_control");  // Set INTCON, (mask) → Result<(), E>
                                                                       // 0 = compare against the previous pin value
    chip.configure_int_pin(false, false).expect("configure_int_pin");  // Configure INT, (open_drain, active_high) → Result<(), E>
                                                                       // push-pull, active-low (IOCON ODR/INTPOL)
    chip.set_sequential(true).expect("set_sequential");                // Sequential access, (enabled) → Result<(), E>
                                                                       // IOCON SEQOP = 0 (driver default)
    chip.enable_pin_interrupt(0xF0).expect("enable_pin_interrupt");    // Arm interrupt-on-change, (mask) → Result<(), E>
                                                                       // GP4–GP7
    let flags = chip.poll_interrupt().expect("poll_interrupt");        // Read INTF and clear, () → Result<u8, E>
                                                                       // INTCAP is read and discarded
    let cap = chip.read_capture().expect("read_capture");              // Read captured port, () → Result<u8, E>
                                                                       // port snapshot at the interrupt
    println!("INTF=0x{:02X} INTCAP=0x{:02X}", flags, cap);

    let iodir = chip.read_register(0x00).expect("read_register");      // Read raw register, (reg) → Result<u8, E>
                                                                       // escape hatch for debugging
    println!("IODIR=0x{:02X}", iodir);
    chip.write_register(0x02, 0x10).expect("write_register");          // Write raw register, (reg, value) → Result<(), E>
                                                                       // GPINTEN: arm GP4
    chip.reset().expect("reset");                                      // Restore reset values, () → Result<(), E>
                                                                       // rewrites all registers, clears shadows

    loop { delay.delay_ms(1000); }
}
