#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::display::{Pcf8576Full, SEVEN_SEG};

esp_app_desc!();

const ADDR: u8 = 0x38;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    // --- 4-digit countdown from 9999 to 0000 on a 1:4 multiplex 7-segment LCD ---
    // The PCF8576 drives four 7-segment digits from a single I2C bus; the host
    // encodes each digit using the chip's 1:4 multiplex bit layout (a/c/b/DP/f/e/g/d)
    // and writes all four with one write_raw() call. The countdown runs once per
    // second and the terminal mirrors the value sent to the display.
    let mut lcd = Pcf8576Full::new(i2c, ADDR).expect("init PCF8576"); // Create PCF8576 driver, (i2c, ADDR=0x38)

    for n in (0..=9999u16).rev() {
        let d0 = ((n / 1000) % 10) as usize;
        let d1 = ((n / 100) % 10) as usize;
        let d2 = ((n / 10) % 10) as usize;
        let d3 = (n % 10) as usize;
        let out: [u8; 4] = [
            SEVEN_SEG[d0], // Encode 7-segment digit, (digit 0–9) → u8
            SEVEN_SEG[d1], // Encode 7-segment digit, (digit 0–9) → u8
            SEVEN_SEG[d2], // Encode 7-segment digit, (digit 0–9) → u8
            SEVEN_SEG[d3], // Encode 7-segment digit, (digit 0–9) → u8
        ];
        lcd.write_raw(0, &out).expect("write_raw"); // Write all four digits, (address 0, 4 bytes) → ()
        println!("countdown: {:04}", n);
        delay.delay_ms(1000);
    }

    // --- Stop indicator: light only the middle segments (g) on every digit ---
    // When the counter reaches zero we replace the "0000" pattern with "----" to
    // signal that the demo has finished. Each digit's g segment is bit 1, so a
    // 0x02 byte lights just the bar across the middle.
    let dash: [u8; 4] = [0x02, 0x02, 0x02, 0x02];
    lcd.write_raw(0, &dash).expect("write_raw"); // Write dash pattern, (address 0, 4 bytes) → ()
    println!("countdown complete");
    loop {}
}
