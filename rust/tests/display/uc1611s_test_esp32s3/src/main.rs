#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::display::uc1611s::Uc1611sFull;

esp_app_desc!();

const TEST_ADDR: u8 = 0x38;

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

    let mut chip = match Uc1611sFull::new(i2c, TEST_ADDR, Delay::new(), 240, 128, false, 0x8F) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach UC1611s at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    check_true!(chip.width() == 240 && chip.height() == 128, "geometry_240x128", passed, failed);

    let s = chip.read_status().expect("read_status");
    check_true!(s[0] & 0x80 != 0, "status_version_bit", passed, failed);
    check_true!(s[2] >> 4 == 1, "status_product_code_1", passed, failed);
    check_true!(s[0] & 0x08 != 0, "status_display_enabled", passed, failed);
    check_true!(s[0] & 0x40 != 0 && s[0] & 0x20 == 0, "status_mirror_x_bottom_view", passed, failed);

    chip.sleep().expect("sleep");
    check_true!(chip.read_status().expect("read_status")[0] & 0x08 == 0, "sleep_clears_display_enable", passed, failed);
    chip.wake().expect("wake");
    check_true!(chip.read_status().expect("read_status")[0] & 0x08 != 0, "wake_sets_display_enable", passed, failed);

    chip.set_flip(false, true).expect("set_flip");
    let s = chip.read_status().expect("read_status");
    check_true!(s[0] & 0x40 == 0 && s[0] & 0x20 != 0, "flip_my_status", passed, failed);
    chip.set_flip(true, false).expect("set_flip");
    let s = chip.read_status().expect("read_status");
    check_true!(s[0] & 0x40 != 0 && s[0] & 0x20 == 0, "flip_mx_status", passed, failed);

    // RAM read-back: draw, show, read pages 0 and 3 back and compare with the framebuffer.
    chip.fill(false);
    chip.draw_line(0, 0, 31, 7, true);
    chip.draw_line(0, 31, 31, 24, true);
    chip.draw_rect(40, 4, 20, 20, true, false);
    chip.show().expect("show");
    let (mut match0, mut match3) = (true, true);
    for half in 0..2i32 {
        let mut ram = [0u8; 32];
        chip.read_ram(0, (half * 32) as u8, &mut ram).expect("read_ram");
        for x in 0..32i32 {
            let expected = (0..8).fold(0u8, |a, b| if chip.get_pixel(half * 32 + x, b) { a | 1 << b } else { a });
            match0 &= ram[x as usize] == expected;
        }
        chip.read_ram(3, (half * 32) as u8, &mut ram).expect("read_ram");
        for x in 0..32i32 {
            let expected = (0..8).fold(0u8, |a, b| if chip.get_pixel(half * 32 + x, 24 + b) { a | 1 << b } else { a });
            match3 &= ram[x as usize] == expected;
        }
    }
    check_true!(match0, "ram_readback_page0", passed, failed);
    check_true!(match3, "ram_readback_page3", passed, failed);

    chip.set_contrast(0x70).expect("set_contrast");
    chip.set_contrast(0x8F).expect("set_contrast");
    chip.set_invert(true).expect("set_invert");
    chip.set_invert(false).expect("set_invert");
    chip.set_all_on(true).expect("set_all_on");
    chip.set_all_on(false).expect("set_all_on");
    chip.set_scroll_line(16).expect("set_scroll_line");
    chip.set_scroll_line(0).expect("set_scroll_line");
    chip.set_fixed_lines(0).expect("set_fixed_lines");
    chip.set_bias_ratio(2).expect("set_bias_ratio");
    chip.set_temperature_compensation(1).expect("set_temperature_compensation");
    chip.set_line_rate(3).expect("set_line_rate");
    chip.set_pump(true).expect("set_pump");
    chip.set_panel_loading(true).expect("set_panel_loading");
    chip.set_partial_display(true, 0, Some(127)).expect("set_partial_display");
    chip.set_partial_display(false, 0, None).expect("set_partial_display");
    chip.set_nline_inversion(0, false).expect("set_nline_inversion");
    chip.set_isolation_clock(1, 0).expect("set_isolation_clock");
    chip.set_write_pattern(1);
    chip.show_page(0).expect("show_page");
    chip.set_write_pattern(0);
    chip.set_display_pattern(1).expect("set_display_pattern");
    chip.set_display_pattern(0).expect("set_display_pattern");
    chip.show().expect("show");
    check_true!(true, "commands_accepted", passed, failed);

    chip.reset().expect("reset");
    check_true!(chip.read_status().expect("read_status")[0] & 0x08 != 0, "reset_restores_display_enable", passed, failed);
    println!("INFO driver retries {}, unrecovered {}", chip.retry_count(), chip.error_count());
    check_true!(chip.ok(), "no_unrecovered_transfer_errors", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
