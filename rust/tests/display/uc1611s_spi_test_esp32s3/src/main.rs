#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::{Level, Output, OutputConfig};
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::chips::display::uc1611s::{Error, Uc1611sSpiFull};
use periph::connection::display::{DisplayConnection, NoPin, WireMode};

esp_app_desc!();

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

    // SPI2: SI on GPIO3 (MOSI), SCL on GPIO5 (SCK); CSB on GPIO4 (driven by ExclusiveDevice).
    let spi_bus = Spi::new(peripherals.SPI2, Config::default())
        .unwrap()
        .with_mosi(peripherals.GPIO3)
        .with_sck(peripherals.GPIO5);
    let cs = Output::new(peripherals.GPIO4, Level::High, OutputConfig::default());
    let device = ExclusiveDevice::new(spi_bus, cs, Delay::new()).unwrap();

    // RS (C/D) on GPIO6, RESET on GPIO7 (no BUSY line).
    let dc = Output::new(peripherals.GPIO6, Level::Low, OutputConfig::default());
    let res = Output::new(peripherals.GPIO7, Level::High, OutputConfig::default());

    let connection = DisplayConnection::new(device, dc, Some(res), None::<NoPin>, Delay::new(), WireMode::Simplex)
        .unwrap();

    let mut passed = 0i32;
    let mut failed = 0i32;

    // Hardware smoke test: the picture itself is verified by eye (see the demo example).
    let mut chip = Uc1611sSpiFull::new_spi(connection, 240, 128, false, 0x8F).unwrap();
    check_true!(true, "init_valid", passed, failed);
    check_true!(chip.width() == 240 && chip.height() == 128, "geometry_240x128", passed, failed);

    chip.clear();
    chip.draw_rect(0, 0, 240, 128, true, false);
    chip.draw_line(0, 0, 239, 127, true);
    chip.draw_rect(20, 20, 40, 30, true, true);
    let mut ok = chip.show().is_ok() && chip.ok();
    check_true!(ok, "show_accepted", passed, failed);
    ok = chip.show_page(3).is_ok()
        && chip.set_contrast(0x70).is_ok()
        && chip.set_contrast(0x8F).is_ok()
        && chip.set_invert(true).is_ok()
        && chip.set_invert(false).is_ok()
        && chip.set_all_on(true).is_ok()
        && chip.set_all_on(false).is_ok()
        && chip.set_flip(false, true).is_ok()
        && chip.set_flip(true, false).is_ok()
        && chip.set_fixed_lines(2).is_ok()
        && chip.set_scroll_line(8).is_ok()
        && chip.set_scroll_line(0).is_ok()
        && chip.set_fixed_lines(0).is_ok()
        && chip.set_bias_ratio(2).is_ok()
        && chip.set_temperature_compensation(1).is_ok()
        && chip.set_line_rate(3).is_ok()
        && chip.set_pump(true).is_ok()
        && chip.set_panel_loading(true).is_ok()
        && chip.set_nline_inversion(0, false).is_ok()
        && chip.set_isolation_clock(1, 0).is_ok()
        && chip.set_partial_display(true, 32, Some(95)).is_ok()
        && chip.set_partial_display(false, 0, None).is_ok()
        && chip.show().is_ok()
        && chip.set_display_pattern(1).is_ok()
        && chip.set_display_pattern(0).is_ok();
    check_true!(ok, "commands_accepted", passed, failed);
    ok = chip.sleep().is_ok() && chip.wake().is_ok() && chip.reset().is_ok() && chip.show().is_ok();
    check_true!(ok, "sleep_wake_reset_accepted", passed, failed);

    let mut buf = [0u8; 4];
    check_true!(matches!(chip.read_status(), Err(Error::Unsupported)), "read_status_unsupported", passed, failed);
    check_true!(matches!(chip.read_ram(0, 0, &mut buf), Err(Error::Unsupported)), "read_ram_unsupported", passed, failed);
    check_true!(chip.retry_count() == 0 && chip.error_count() == 0, "no_retries_or_errors", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
