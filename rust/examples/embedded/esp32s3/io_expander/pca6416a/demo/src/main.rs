#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::io_expander::Pca6416aFull;

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

    let chip = Pca6416aFull::new(i2c, ADDR).expect("init PCA6416A");       // Create PCA6416A driver, (i2c, addr=0x20) → Result

    // --- Configure LEDs and buttons ---
    // Port 0 holds the active-low LED outputs (0 lights the LED); port 1 stays
    // input and relies on external pull-ups (the chip has none), so a pressed
    // button (to GND) reads 0. Polarity inversion makes it read 1.
    chip.write_all(0xFFFF).expect("write_all");                           // Turn all LEDs off, (value=0xFFFF) → Result<(), E>
    chip.set_direction(0xFF00).expect("set_direction");                    // Port 0 outputs, port 1 inputs, (mask) → Result<(), E>
    chip.configure_polarity(0xFF00).expect("configure_polarity");          // Invert port 1 inputs, (mask) → Result<(), E>

    // --- Scanner loop ---
    // Any button edge changes INPUT; the loop reads the changed mask with
    // poll_interrupt() (the INT line would trigger this read) and holds the LED
    // matching the pressed button for one second.
    let mut hold_steps = 0u32;
    let mut hold_mask = 0xFFu8;
    for step in 0..80u32 {
        let changed = chip.poll_interrupt().expect("poll_interrupt");      // Read INPUT0/1, get changed mask, () → Result<u16, E>
        if changed != 0 {
            let all = chip.read_all().expect("read_all");                  // Read all 16 pins, () → Result<u16, E>
            println!("button changed=0x{:04X} all=0x{:04X}", changed, all);
            hold_mask = !(((changed >> 8) & 0xFF) as u8);
            hold_steps = 10;
        }
        let pos = step % 8;
        let leds = if hold_steps > 0 { hold_steps -= 1; hold_mask } else { !(1u8 << pos) };
        chip.write_all(0xFF00 | leds as u16).expect("write_all");           // Write LED outputs, (value) → Result<(), E>
        let all = chip.read_all().expect("read_all");                      // Read all 16 pins, () → Result<u16, E>
        println!("  pos={}  all=0x{:04X}", pos, all);
        delay.delay_ms(100);
    }

    // --- Shut down ---
    chip.write_all(0xFFFF).expect("write_all");                           // Turn all LEDs off, (value=0xFFFF) → Result<(), E>
    loop { delay.delay_ms(1000); }
}
