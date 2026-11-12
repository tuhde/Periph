#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::display::ssd1305::{AddressingMode, Ssd1305Full};

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

    let mut chip = match Ssd1305Full::new(i2c, TEST_ADDR, Delay::new(), 128, 64, 4) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach SSD1305 at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    check_true!(chip.width() == 128 && chip.height() == 64, "geometry_128x64", passed, failed);

    let s = chip.read_status().expect("read_status");
    check_true!(s & 0x40 == 0, "status_display_on", passed, failed);
    check_true!(s & 0x40 == 0, "status_display_on", passed, failed);

    chip.sleep().expect("sleep");
    check_true!(chip.read_status().expect("read_status") & 0x40 != 0, "sleep_sets_off_bit", passed, failed);
    chip.wake().expect("wake");
    check_true!(chip.read_status().expect("read_status") & 0x40 == 0, "wake_clears_off_bit", passed, failed);

    // RAM read-back: draw, show, read pages 0 and 3 back and compare with the framebuffer.
    chip.fill(false);
    chip.draw_line(0, 0, 31, 7, true);
    chip.draw_line(0, 31, 31, 24, true);
    chip.draw_rect(40, 4, 20, 20, true, false);
    chip.show().expect("show");
    let mut ram = [0u8; 16];
    let (mut match0, mut match3) = (true, true);
    for part in 0..4i32 {
        chip.read_page(0, (2 + part * 16) as u8, &mut ram).expect("read_page");
        for x in 0..16i32 {
            let mut expected = 0u8;
            for b in 0..8 {
                if chip.get_pixel(part * 16 + x, b) {
                    expected |= 1 << b;
                }
            }
            if ram[x as usize] != expected {
                match0 = false;
            }
        }
        chip.read_page(3, (2 + part * 16) as u8, &mut ram).expect("read_page");
        for x in 0..16i32 {
            let mut expected = 0u8;
            for b in 0..8 {
                if chip.get_pixel(part * 16 + x, 24 + b) {
                    expected |= 1 << b;
                }
            }
            if ram[x as usize] != expected {
                match3 = false;
            }
        }
    }
    check_true!(match0, "ram_readback_page0", passed, failed);
    check_true!(match3, "ram_readback_page3", passed, failed);

    chip.set_contrast(0x40).expect("set_contrast");
    chip.set_contrast(0x80).expect("set_contrast");
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
    chip.set_clock(1, 7).expect("set_clock");
    chip.set_precharge(2, 2).expect("set_precharge");
    chip.set_com_config(true, false).expect("set_com_config");
    chip.set_vcomh(0x34).expect("set_vcomh");
    chip.set_dim_mode(0x40, 0x40).expect("set_dim_mode");
    chip.set_dim(true).expect("set_dim");
    chip.set_dim(false).expect("set_dim");
    chip.set_scroll_horizontal(true, 0, 7, 0, 1).expect("set_scroll_horizontal");
    chip.scroll_start().expect("scroll_start");
    chip.scroll_stop().expect("scroll_stop");
    chip.set_addressing_mode(AddressingMode::Horizontal).expect("set_addressing_mode");
    chip.show().expect("show");
    chip.set_addressing_mode(AddressingMode::Page).expect("set_addressing_mode");
    chip.show_page(0).expect("show_page");
    chip.show().expect("show");
    check_true!(chip.read_status().expect("read_status") & 0x40 == 0, "commands_accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
