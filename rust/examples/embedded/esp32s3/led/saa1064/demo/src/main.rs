//! Demo for the SAA1064 — four-digit counter, decimal point sweep, current steps, static mode and segment test. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::led::SAA1064Full;

esp_app_desc!();

const ADDR: u8 = 0x38;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    #[allow(unused_mut)]
    let mut delay = Delay::new();
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }

    // Create SAA1064 driver, (i2c, addr=0x38) → SAA1064
    let mut chip = SAA1064Full::new(i2c, ADDR).expect("new");

    // --- Four-digit counter ---
    // A fast counter from 0 to 9999, the decimal point sweeping across the four positions,
    // the segment current stepped through 3/9/15/21 mA, a switch to static mode (only digits
    // 1 and 2 stay lit), a 2 s segment test and the status byte (the PR flag is set after
    // power-up and clears on the first read).
    let mut n: u16 = 0;
    while n < 10000 {
        // Show the counter, (value 0–9999) → Result<bool>
        chip.show_number(n).expect("show_number");
        sleep_ms!(10);
        n += 37;
    }

    // Show four digits, (digits) → Result<bool>
    chip.show_digits([Some(1), Some(2), Some(3), Some(4)]).expect("show_digits");
    for pos in 1..=4u8 {
        // Light a decimal point, (position, on) → Result<bool>
        chip.set_decimal_point(pos, true).expect("dp on");
        sleep_ms!(400);
        chip.set_decimal_point(pos, false).expect("dp off");
    }

    for ma in [3u8, 9, 15, 21] {
        // Step the segment current, (ma) → Result<bool>
        chip.set_current(ma).expect("set_current");
        chip.show_number(8888).expect("show_number");
        sleep_ms!(1000);
    }
    chip.set_current(12).expect("set_current");

    // Static mode, (dynamic) → Result<()>
    chip.set_mode(false).expect("static");
    sleep_ms!(2000);
    chip.set_mode(true).expect("dynamic");

    // Segment test, (on) → Result<()>
    chip.segment_test(true).expect("test on");
    sleep_ms!(2000);
    chip.segment_test(false).expect("test off");

    // Read the status byte, () → Result<u8>
    println!("status 0x{:02X}", chip.read_status().expect("read_status"));
    // Read the PR flag, () → Result<bool>
    println!("power reset {}", chip.power_reset_flag().expect("power_reset_flag"));
    // Blank all digits, () → Result<()>
    chip.clear().expect("clear");

    loop {
        sleep_ms!(1000);
    }
}
