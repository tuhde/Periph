#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::{Level, Output, OutputConfig};
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::chips::display::ssd1331::{DisplayMode, Ssd1331Full, BLUE, BUFFER_SIZE, GREEN, RED, WHITE, YELLOW};
use periph::connection::display::{DisplayConnection, NoPin, WireMode};

static mut FB: [u8; BUFFER_SIZE] = [0; BUFFER_SIZE];

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

    // SPI2: DIN on GPIO3 (MOSI), CLK on GPIO5 (SCK); CS# on GPIO4 (driven by ExclusiveDevice).
    let spi_bus = Spi::new(peripherals.SPI2, Config::default())
        .unwrap()
        .with_mosi(peripherals.GPIO3)
        .with_sck(peripherals.GPIO5);
    let cs = Output::new(peripherals.GPIO4, Level::High, OutputConfig::default());
    let device = ExclusiveDevice::new(spi_bus, cs, Delay::new()).unwrap();

    // D/C# on GPIO6, RES# on GPIO7 (no BUSY line).
    let dc = Output::new(peripherals.GPIO6, Level::Low, OutputConfig::default());
    let res = Output::new(peripherals.GPIO7, Level::High, OutputConfig::default());

    let connection = DisplayConnection::new(device, dc, Some(res), None::<NoPin>, Delay::new(), WireMode::Simplex)
        .unwrap();

    let mut passed = 0i32;
    let mut failed = 0i32;

    // Hardware smoke test: the picture itself is verified by eye (see the demo example).
    let fb = unsafe { &mut *core::ptr::addr_of_mut!(FB) };
    let mut chip = Ssd1331Full::new(connection, fb).unwrap();
    check_true!(true, "init_valid", passed, failed);

    chip.clear(BLUE);
    check_true!(chip.get_pixel(0, 0) == BLUE && chip.get_pixel(95, 63) == BLUE, "clear_readback", passed, failed);
    chip.set_pixel(5, 6, RED);
    check_true!(chip.get_pixel(5, 6) == RED, "pixel_readback", passed, failed);
    chip.draw_rect(10, 10, 20, 10, GREEN, true);
    check_true!(chip.get_pixel(15, 15) == GREEN, "draw_rect_fill", passed, failed);
    chip.draw_line(0, 0, 95, 63, WHITE);
    check_true!(chip.get_pixel(0, 0) == WHITE && chip.get_pixel(95, 63) == WHITE, "draw_line_endpoints", passed, failed);
    let ok = chip.show().is_ok()
        && chip.show_region(8, 8, 32, 16).is_ok()
        && chip.hw_clear(0, 0, 95, 63).is_ok()
        && chip.hw_rect(10, 10, 40, 30, YELLOW, RED, true).is_ok()
        && chip.hw_line(0, 0, 95, 63, WHITE).is_ok()
        && chip.hw_copy(10, 10, 30, 30, 50, 20).is_ok()
        && chip.hw_dim(0, 0, 47, 63).is_ok()
        && chip.set_contrast(0x80, 0x80, 0x80).is_ok()
        && chip.set_master_current(7).is_ok()
        && chip.set_display_mode(DisplayMode::Inverse).is_ok()
        && chip.set_display_mode(DisplayMode::Normal).is_ok()
        && chip.set_flip(false, false).is_ok()
        && chip.scroll(1, 0, 64, 0, 0).is_ok()
        && chip.scroll_stop().is_ok()
        && chip.display_off().is_ok()
        && chip.display_on().is_ok();
    check_true!(ok, "commands_accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
