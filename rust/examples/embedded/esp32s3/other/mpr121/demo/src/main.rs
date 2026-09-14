#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::other::Mpr121Full;

esp_app_desc!();

static NOTES: [&str; 12] = [
    "C4", "C#4", "D4", "D#4", "E4", "F4", "F#4", "G4", "G#4", "A4", "A#4", "B4",
];

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());                     // Init ESP HAL, () → Peripherals
    let mut delay = Delay::new();                                                     // Create delay provider, () → Delay
    let i2c = I2c::new(peripherals.I2C0, Config::default())                           // Create I²C bus, (I2C0, config=default 100kHz) → I2c
        .unwrap()
        .with_sda(peripherals.GPIO8)
        .with_scl(peripherals.GPIO9);

    let mut chip = Mpr121Full::new(i2c, 0x5A, &mut delay).unwrap();                  // Construct MPR121 Full, (i2c, addr=0x5A, delay) → Result<Mpr121Full, _>
    let mut previous: u16 = 0;

    loop {
        let mask = chip.touched().unwrap();                                            // Read 12-bit touch bitmask, () → Result<u16, _> bitmask
        let newly_pressed = mask & !previous;
        let newly_released = !mask & previous;
        for n in 0..12u8 {
            if newly_pressed & (1u16 << n) != 0 {
                println!("NOTE ON:  {}", NOTES[n as usize]);
            }
            if newly_released & (1u16 << n) != 0 {
                println!("NOTE OFF: {}", NOTES[n as usize]);
            }
        }
        previous = mask;
        delay.delay_ms(50);
    }
}
