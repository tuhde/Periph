#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::{Level, Output, OutputConfig};
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::chips::display::st7036::{St7036Full, Supply};
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
    let mut chip = St7036Full::new(connection, 2, None, Supply::V3_3, None).unwrap();
    check_true!(true, "init_valid", passed, failed);
    check_true!(chip.columns() == 16 && chip.rows() == 2, "geometry_16x2", passed, failed);

    let mut ok = chip.clear().is_ok()
        && chip.print_at(0, 0, "Periph ST7036").is_ok()
        && chip.print_at(0, 1, "smoke test").is_ok();
    check_true!(ok, "text_accepted", passed, failed);
    ok = chip.create_char(0, &[0x1F, 0x11, 0x15, 0x11, 0x1F, 0x00, 0x1F, 0x00]).is_ok()
        && chip.set_cursor(15, 1).is_ok()
        && chip.write_char(0).is_ok()
        && chip.cursor(true, true).is_ok()
        && chip.cursor(false, false).is_ok()
        && chip.set_entry_mode(true, false).is_ok()
        && chip.shift_display(true).is_ok()
        && chip.shift_display(false).is_ok()
        && chip.shift_cursor(true).is_ok()
        && chip.return_home().is_ok()
        && chip.set_ddram_address(0x40).is_ok()
        && chip.set_contrast(40).is_ok()
        && chip.set_booster(true).is_ok()
        && chip.set_follower(true, 5).is_ok()
        && chip.set_bias(false).is_ok()
        && chip.set_contrast(24).is_ok()
        && chip.display(false).is_ok()
        && chip.display(true).is_ok()
        && chip.home().is_ok();
    check_true!(ok, "commands_accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
