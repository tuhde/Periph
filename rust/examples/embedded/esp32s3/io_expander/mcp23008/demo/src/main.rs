#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::Mcp23008Full;

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

    // --- Configure LEDs and buttons ---
    // GP0–GP3 are LED outputs; GP4–GP7 stay inputs with the 100 kΩ pull-ups
    // on, so a pressed button (to GND) reads 0.
    for n in 0..4 {
        chip.pin(n).into_output().expect("into_output");               // Make LED pin an output, () → Result<(), E>
    }
    chip.configure_pullup(0xF0).expect("configure_pullup");            // Pull-ups on GP4–GP7, (mask) → Result<(), E>

    // --- Arm interrupt-on-change on the buttons ---
    // Any button edge latches INTF; the loop below reads it with
    // poll_interrupt() and the snapshot with read_capture().
    chip.enable_pin_interrupt(0xF0).expect("enable_pin_interrupt");    // Arm GP4–GP7, (mask) → Result<(), E>

    // --- Scanner loop ---
    let mut hold_steps = 0u32;
    let mut hold_mask = 0u8;
    for step in 0..40u32 {
        let flags = chip.poll_interrupt().expect("poll_interrupt");    // Read INTF and clear, () → Result<u8, E>
        if flags != 0 {
            let captured = chip.read_capture().expect("read_capture"); // Read port snapshot, () → Result<u8, E>
            println!("button INTF=0x{:02X} INTCAP=0x{:02X}", flags, captured);
            hold_mask = (flags >> 4) & 0x0F;
            hold_steps = 10;
        }
        let pos = step % 4;
        let leds = if hold_steps > 0 { hold_steps -= 1; hold_mask } else { 1 << pos };
        chip.write_port(leds).expect("write_port");                    // Write LED outputs, (mask) → Result<(), E>
        let port = chip.read_port().expect("read_port");               // Read all 8 pins, () → Result<u8, E>
        println!("  pos={}  port=0x{:02X}", pos, port);
        delay.delay_ms(100);
    }

    // --- Shut down ---
    chip.write_port(0x00).expect("write_port");                        // Clear all outputs, (mask=0x00) → Result<(), E>

    loop { delay.delay_ms(1000); }

    loop { delay.delay_ms(1000); }
}
