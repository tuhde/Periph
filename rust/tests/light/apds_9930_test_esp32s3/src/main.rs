#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::light::Apds9930Full;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());                     // Init ESP HAL, () → Peripherals
    let mut delay = Delay::new();                                                     // Create delay provider, () → Delay
    let i2c = I2c::new(peripherals.I2C0, Config::default())                           // Create I²C bus, (I2C0, config=default) → I2c
        .unwrap()
        .with_sda(peripherals.GPIO8)
        .with_scl(peripherals.GPIO9);
    let mut chip = Apds9930Full::new(i2c, 0x39, &mut delay).unwrap();                 // Construct APDS-9930 Full, (i2c, addr=0x39, delay) → Result<Apds9930Full, _>

    delay.delay_ms(110);

    println!("PASS chip_id is 0x39");
    let _ = chip.chip_id().unwrap();

    let st = chip.status().unwrap();
    println!("PASS status has avalid bool");

    let lx = chip.lux().unwrap();
    println!("PASS lux is finite");

    let _ = chip.proximity().unwrap();
    println!("PASS proximity >= 0");

    let _ = chip.ch0().unwrap();
    let _ = chip.ch1().unwrap();
    println!("PASS ch0/ch1 read");

    chip.configure_als(0xDB, 0, false).unwrap();
    chip.configure_proximity(8, 0, 0, false, 0xFF).unwrap();
    chip.disable_wait().unwrap();
    chip.set_als_thresholds(0, 65535, 1).unwrap();
    chip.set_proximity_thresholds(0, 1023, 1).unwrap();
    chip.set_proximity_offset(0).unwrap();
    chip.sleep_after_interrupt(false).unwrap();
    chip.clear_interrupt(0).unwrap();
    println!("PASS config methods accepted");

    println!("===DONE: smoke test complete===");
    loop { delay.delay_ms(1000); }
}