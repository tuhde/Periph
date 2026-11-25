#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::Pca6408aFull;

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

    let chip = Pca6408aFull::new(i2c, ADDR).expect("init PCA6408A");       // Create PCA6408A driver, (i2c, addr=0x20) → Result

    // --- Configure LEDs and buttons ---
    // P0–P3 are active-low LED outputs (0 lights the LED); P4–P7 stay inputs
    // and rely on external pull-ups (the chip has none), so a pressed button
    // (to GND) reads 0.
    chip.write_port(0xFF).expect("write_port");                            // Turn all LEDs off, (mask=0xFF) → Result<(), E>
    chip.set_direction(0xF0).expect("set_direction");                      // P0–P3 outputs, P4–P7 inputs, (mask) → Result<(), E>

    // --- Scanner loop ---
    // Any button edge changes INPUT; the loop reads the changed mask with
    // poll_interrupt() (the INT line would trigger this read) and holds the LED
    // matching the pressed button for one second.
    let mut hold_steps = 0u32;
    let mut hold_mask = 0x0Fu8;
    for step in 0..40u32 {
        let changed = chip.poll_interrupt().expect("poll_interrupt");      // Read INPUT, get changed mask, () → Result<u8, E>
        if changed != 0 {
            let port = chip.read_port().expect("read_port");               // Read all 8 pins, () → Result<u8, E>
            println!("button changed=0x{:02X} port=0x{:02X}", changed, port);
            hold_mask = !((changed >> 4) & 0x0F) & 0x0F;
            hold_steps = 10;
        }
        let pos = step % 4;
        let leds = if hold_steps > 0 { hold_steps -= 1; hold_mask } else { !(1u8 << pos) & 0x0F };
        chip.write_port(0xF0 | leds).expect("write_port");                 // Write LED outputs, (mask) → Result<(), E>
        let port = chip.read_port().expect("read_port");                   // Read all 8 pins, () → Result<u8, E>
        println!("  pos={}  port=0x{:02X}", pos, port);
        delay.delay_ms(100);
    }

    // --- Shut down ---
    chip.write_port(0xFF).expect("write_port");                            // Turn all LEDs off, (mask=0xFF) → Result<(), E>
    loop { delay.delay_ms(1000); }
}
