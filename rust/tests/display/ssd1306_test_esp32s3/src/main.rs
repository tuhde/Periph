#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::display::ssd1306::{AddressingMode, Error, Ssd1306Full};

esp_app_desc!();

const TEST_ADDR: u8 = 0x3C;

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

    let mut chip = match Ssd1306Full::new(i2c, TEST_ADDR, Delay::new(), 128, 64, false) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach SSD1306 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    check_true!(chip.width() == 128 && chip.height() == 64, "geometry_128x64", passed, failed);

    // The SSD1306 cannot be read over its serial interfaces: the picture is checked by eye.
    chip.fill(false);
    chip.draw_line(0, 0, 31, 7, true);
    chip.draw_line(0, 31, 31, 24, true);
    chip.draw_rect(40, 4, 20, 20, true, false);
    chip.show().expect("show");
    check_true!(chip.get_pixel(0, 0) && chip.get_pixel(40, 4), "framebuffer_pixels", passed, failed);

    chip.set_contrast(0x40).expect("set_contrast");
    chip.set_contrast(0x7F).expect("set_contrast");
    chip.set_invert(true).expect("set_invert");
    chip.set_invert(false).expect("set_invert");
    chip.set_all_on(true).expect("set_all_on");
    chip.set_all_on(false).expect("set_all_on");
    chip.set_flip(true, true).expect("set_flip");
    chip.set_flip(false, false).expect("set_flip");
    chip.set_start_line(16).expect("set_start_line");
    chip.set_start_line(0).expect("set_start_line");
    chip.set_display_offset(0).expect("set_display_offset");
    chip.set_multiplex(64).expect("set_multiplex");
    chip.set_clock(1, 8).expect("set_clock");
    chip.set_precharge(2, 2).expect("set_precharge");
    chip.set_com_config(true, false).expect("set_com_config");
    chip.set_vcomh(0x20).expect("set_vcomh");
    chip.set_charge_pump(true).expect("set_charge_pump");
    chip.set_scroll_horizontal(true, 0, 7, 7).expect("set_scroll_horizontal");
    chip.scroll_start().expect("scroll_start");
    check_true!(matches!(chip.show(), Err(Error::ScrollActive)), "show_refused_while_scrolling", passed, failed);
    chip.scroll_stop().expect("scroll_stop");
    chip.set_addressing_mode(AddressingMode::Horizontal).expect("set_addressing_mode");
    chip.show().expect("show");
    chip.set_addressing_mode(AddressingMode::Page).expect("set_addressing_mode");
    chip.show_page(0).expect("show_page");
    chip.show().expect("show");
    check_true!(true, "commands_accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
