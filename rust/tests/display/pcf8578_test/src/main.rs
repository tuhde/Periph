use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::display::pcf8578::{ACCESS_CHARACTER, ACCESS_FULL_GRAPHIC, ACCESS_HALF_GRAPHIC, DISPLAY_ALL_ON, DISPLAY_INVERSE, DISPLAY_NORMAL};
use periph::chips::display::{Pcf8578Full, Pcf8578Minimal};

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x3C);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    // Minimal
    let mut lcd = Pcf8578Minimal::new(dev, addr, 16, &mut Delay).expect("init PCF8578");
    check_true!(lcd.clear().is_ok(), "clear", passed, failed);
    check_true!(lcd.set_pixel(0, 0, true).is_ok() && lcd.set_pixel(23, 15, true).is_ok(), "set_pixel", passed, failed);
    check_true!(lcd.set_pixel(30, 30, true).is_ok(), "set_pixel_out_of_range_ignored", passed, failed);
    check_true!(lcd.write_columns(2, 1, &[0xFF, 0x81, 0xFF]).is_ok(), "write_columns", passed, failed);
    let dev = lcd.release();

    // Full: readRam verifies the bytes really landed
    let mut full = Pcf8578Full::new(dev, addr, 16, &mut Delay).expect("init PCF8578 full");
    check_true!(full.write_ram(16, 0, &[0xAA, 0x55, 0x0F], ACCESS_CHARACTER).is_ok(), "write_ram", passed, failed);
    let mut buf = [0u8; 3];
    let read_ok = full.read_ram(16, 0, &mut buf, ACCESS_CHARACTER).is_ok();
    check_true!(read_ok && buf == [0xAA, 0x55, 0x0F], "ram_roundtrip", passed, failed);

    let graphics_ok = full.fill(true).is_ok()
        && full.fill(false).is_ok()
        && full.draw_line(0, 0, 23, 15, true).is_ok()
        && full.draw_rect(0, 0, 24, 16, true, false).is_ok()
        && full.draw_rect(4, 4, 8, 8, true, true).is_ok()
        && full.show_bitmap(0, 0, &[&[0x18, 0x24, 0x42, 0x81]]).is_ok();
    check_true!(graphics_ok && full.get_pixel(0, 0), "graphics", passed, failed);

    let config_ok = full.set_display(DISPLAY_INVERSE).is_ok()
        && full.set_display(DISPLAY_ALL_ON).is_ok()
        && full.set_display(DISPLAY_NORMAL).is_ok()
        && full.set_start_bank(1).is_ok()
        && full.scroll(1).is_ok()
        && full.scroll(-2).is_ok()
        && full.device_select(1).is_ok()
        && full.device_select(0).is_ok()
        && full.write_ram(16, 0, &[0u8; 8], ACCESS_HALF_GRAPHIC).is_ok()
        && full.write_ram(16, 0, &[0u8; 8], ACCESS_FULL_GRAPHIC).is_ok()
        && full.set_mode(24, true).is_ok()
        && full.set_mode(16, true).is_ok()
        && full.reset().is_ok();
    check_true!(config_ok, "configuration", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
}
