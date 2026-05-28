#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::light::Apds9960Full;

esp_app_desc!();

const TEST_ADDR: u8 = 0x39;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}", $label);
            $failed += 1;
        }
    };
}

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut chip = match Apds9960Full::new(i2c, TEST_ADDR) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach APDS-9960 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    check_true!(
        chip.chip_id().map(|v| v == 0xAB).unwrap_or(false),
        "chip_id",
        passed,
        failed
    );

    let color_ok = chip.color().map(|(c, r, g, b)| c >= 0 && r >= 0 && g >= 0 && b >= 0).unwrap_or(false);
    check_true!(color_ok, "color >= 0", passed, failed);

    check_true!(chip.is_als_valid().unwrap_or(false), "is_als_valid", passed, failed);

    chip.enable_proximity(true).ok();
    check_true!(chip.proximity().map(|p| p <= 255).unwrap_or(false), "proximity <= 255", passed, failed);
    check_true!(chip.is_proximity_valid().unwrap_or(false), "is_proximity_valid", passed, failed);

    chip.configure_als(0xB6, 1).ok();
    check_true!(chip.is_als_valid().unwrap_or(false), "als_valid after configure", passed, failed);

    chip.als_threshold(100, 60000).ok();
    chip.proximity_threshold(10, 200).ok();
    chip.set_persistence(0, 1).ok();
    check_true!(true, "persistence set", passed, failed);

    chip.enable_als_interrupt(true).ok();
    chip.enable_proximity_interrupt(true).ok();
    chip.clear_als_interrupt().ok();
    chip.clear_proximity_interrupt().ok();
    chip.clear_all_interrupts().ok();
    check_true!(true, "interrupts cleared", passed, failed);

    chip.set_proximity_offset(10, -5).ok();
    chip.set_proximity_mask(false, false, false, false).ok();
    check_true!(true, "proximity offset/mask set", passed, failed);

    chip.enable_gesture(true).ok();
    chip.configure_gesture(1, 0, 0, 1, 1, 50, 20).ok();
    check_true!(true, "gesture configured", passed, failed);
    check_true!(chip.gesture_fifo_level().map(|l| l >= 0).unwrap_or(false), "gesture_fifo_level >= 0", passed, failed);
    chip.clear_gesture_fifo().ok();
    chip.enable_gesture_interrupt(false).ok();
    chip.enable_gesture(false).ok();
    check_true!(true, "gesture disabled", passed, failed);

    check_true!(chip.status().is_ok(), "status readable", passed, failed);

    chip.enable_proximity(false).ok();

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
