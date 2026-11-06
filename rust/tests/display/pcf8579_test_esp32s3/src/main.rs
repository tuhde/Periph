#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::display::pcf8579::{ACCESS_CHARACTER, ACCESS_FULL_GRAPHIC, ACCESS_HALF_GRAPHIC, DISPLAY_ALL_ON, DISPLAY_INVERSE, DISPLAY_NORMAL};
use periph::chips::display::{Pcf8579Full, Pcf8579Minimal};

esp_app_desc!();

const TEST_ADDR: u8 = 0x3C;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    // PCF8579 supports 100 kHz at most
    let dev = I2c::new(peripherals.I2C0, Config::default().with_frequency(esp_hal::time::Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let addr = TEST_ADDR;
    let mut passed = 0i32;
    let mut failed = 0i32;

    // Minimal
    let mut lcd = Pcf8579Minimal::new(dev, addr, 0, 16, false, &mut Delay::new()).expect("init PCF8579");
    check_true!(lcd.clear().is_ok(), "clear", passed, failed);
    check_true!(lcd.set_pixel(0, 0, true).is_ok() && lcd.set_pixel(39, 15, true).is_ok(), "set_pixel", passed, failed);
    check_true!(lcd.set_pixel(45, 30, true).is_ok(), "set_pixel_out_of_range_ignored", passed, failed);
    check_true!(lcd.write_columns(2, 1, &[0xFF, 0x81, 0xFF]).is_ok(), "write_columns", passed, failed);
    let dev = lcd.release();

    // Full: readRam verifies the bytes really landed
    let mut full = Pcf8579Full::new(dev, addr, 0, 16, false, &mut Delay::new()).expect("init PCF8579 full");
    check_true!(full.write_ram(16, 0, &[0xAA, 0x55, 0x0F], ACCESS_CHARACTER).is_ok(), "write_ram", passed, failed);
    let mut buf = [0u8; 3];
    let read_ok = full.read_ram(16, 0, &mut buf, ACCESS_CHARACTER).is_ok();
    check_true!(read_ok && buf == [0xAA, 0x55, 0x0F], "ram_roundtrip", passed, failed);

    let graphics_ok = full.fill(true).is_ok()
        && full.fill(false).is_ok()
        && full.draw_line(0, 0, 39, 15, true).is_ok()
        && full.draw_rect(0, 0, 40, 16, true, false).is_ok()
        && full.draw_rect(4, 4, 8, 8, true, true).is_ok()
        && full.show_bitmap(0, 0, &[0x18, 0x24, 0x42, 0x81]).is_ok();
    check_true!(graphics_ok && full.get_pixel(0, 0), "graphics", passed, failed);

    let config_ok = full.set_display(DISPLAY_INVERSE).is_ok()
        && full.set_display(DISPLAY_ALL_ON).is_ok()
        && full.set_display(DISPLAY_NORMAL).is_ok()
        && full.set_start_bank(1).is_ok()
        && full.scroll(1).is_ok()
        && full.scroll(-2).is_ok()
        && full.write_ram(16, 0, &[0u8; 8], ACCESS_HALF_GRAPHIC).is_ok()
        && full.write_ram(16, 0, &[0u8; 8], ACCESS_FULL_GRAPHIC).is_ok()
        && full.set_mode(24, false).is_ok()
        && full.set_mode(16, false).is_ok()
        && full.reset().is_ok();
    check_true!(config_ok, "configuration", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
