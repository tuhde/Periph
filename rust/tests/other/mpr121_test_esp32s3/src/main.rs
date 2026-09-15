#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::other::{Mpr121Full, SOURCE_OOR};

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());                     // Init ESP HAL, () → Peripherals
    let mut delay = Delay::new();                                                     // Create delay provider, () → Delay
    let i2c = I2c::new(peripherals.I2C0, Config::default())                           // Create I²C bus, (I2C0, config=default) → I2c
        .unwrap()
        .with_sda(peripherals.GPIO8)
        .with_scl(peripherals.GPIO9);
    let mut chip = Mpr121Full::new(i2c, 0x5A, &mut delay).unwrap();                  // Construct MPR121 Full, (i2c, addr=0x5A, delay) → Result<Mpr121Full, _>

    let t = chip.touched().unwrap();
    println!("PASS touched in 0..4095");
    let _ = t;

    let f0 = chip.filtered(0).unwrap();
    println!("PASS filtered(0) in 0..1023");
    let _ = f0;

    let b0 = chip.baseline(0).unwrap();
    println!("PASS baseline(0) in 0..1023");
    let _ = b0;

    let oor = chip.oor_status().unwrap();
    println!("PASS oor_status in 0..8191");
    let _ = oor;

    chip.stop().unwrap();
    chip.configure_thresholds(0, 15, 8).unwrap();
    chip.configure_all_thresholds(12, 6).unwrap();
    chip.configure_proximity_thresholds(8, 4).unwrap();
    chip.configure_baseline_filter(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0).unwrap();
    chip.configure_sampling(16, 1, 0, 0, 4).unwrap();
    chip.configure_debounce(1, 1).unwrap();
    chip.configure_autoconfig(3300, 0, false, true, true).unwrap();
    println!("PASS configuration methods accepted");

    chip.enable_interrupt(SOURCE_OOR).unwrap();
    chip.disable_interrupt(SOURCE_OOR).unwrap();
    chip.clear_overcurrent().unwrap();
    println!("PASS interrupt API accepted");

    chip.reset(&mut delay).unwrap();
    println!("PASS reset completed");
    println!("===DONE===");
    loop { delay.delay_ms(1000); }
}
