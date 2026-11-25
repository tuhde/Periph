#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::Pca9536Full;

esp_app_desc!();

const ADDR: u8 = 0x41;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let chip = Pca9536Full::new(i2c, ADDR).expect("init PCA9536");       // Create PCA9536 driver, (i2c, addr=0x41) → Result

    // --- Configure LEDs and buttons ---
    // P0–P1 are active-low LED outputs (0 lights the LED); P2–P3 stay inputs
    // and rely on the built-in weak pull-up, so a pressed button (to GND)
    // reads 0.
    chip.write_port(0x0F).expect("write_port");                            // Turn all LEDs off, (mask=0x0F) → Result<(), E>
    chip.set_direction(0x0C).expect("set_direction");                      // P0–P1 outputs, P2–P3 inputs, (mask) → Result<(), E>

    // --- Blink loop ---
    // Any button edge changes INPUT; the loop reads the changed mask with
    // poll_changes() and holds the LED matching the pressed button for one
    // second.
    let mut hold_steps = 0u32;
    let mut hold_mask = 0x03u8;
    for step in 0..40u32 {
        let changed = chip.poll_changes().expect("poll_changes");          // Read INPUT, get changed mask, () → Result<u8, E>
        if changed != 0 {
            let port = chip.read_port().expect("read_port");               // Read all 4 pins, () → Result<u8, E>
            println!("button changed=0x{:X} port=0x{:X}", changed, port);
            hold_mask = !((changed >> 2) & 0x03) & 0x03;
            hold_steps = 10;
        }
        let leds = if hold_steps > 0 { hold_steps -= 1; hold_mask } else if step % 2 == 0 { 0x02 } else { 0x01 };
        chip.write_port(0x0C | leds).expect("write_port");                 // Write LED outputs, (mask) → Result<(), E>
        delay.delay_ms(100);
    }

    // --- Shut down ---
    chip.write_port(0x0F).expect("write_port");                            // Turn all LEDs off, (mask=0x0F) → Result<(), E>
    loop { delay.delay_ms(1000); }
}
