#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::{Input, InputConfig, Level, Output, OutputConfig};
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::chips::display::uc8276c::{Uc8276cFull, BLACK, RED, WHITE};
use periph::connection::display::{DisplayConnection, WireMode};

static mut BW: [u8; 15000] = [0; 15000];
static mut RED_PLANE: [u8; 15000] = [0; 15000];

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

    // D/C# on GPIO6, RES# on GPIO7, BUSY on GPIO10.
    let dc = Output::new(peripherals.GPIO6, Level::Low, OutputConfig::default());
    let res = Output::new(peripherals.GPIO7, Level::High, OutputConfig::default());
    let busy = Input::new(peripherals.GPIO10, InputConfig::default());

    let connection = DisplayConnection::new(device, dc, Some(res), Some(busy), Delay::new(), WireMode::Simplex)
        .unwrap();

    let mut passed = 0i32;
    let mut failed = 0i32;

    // Hardware smoke test: needs a panel, show() waits for the real refresh (about 17 s).
    let (bw, red) = unsafe {
        (
            &mut *core::ptr::addr_of_mut!(BW),
            &mut *core::ptr::addr_of_mut!(RED_PLANE),
        )
    };
    let mut chip = Uc8276cFull::new(connection, bw, Some(red), 400, 300, Some(30_000)).unwrap();
    check_true!(true, "init_valid", passed, failed);

    chip.clear(WHITE);
    chip.set_pixel(1, 1, BLACK);
    check_true!(chip.get_pixel(1, 1) == BLACK, "pixel_black_readback", passed, failed);
    chip.set_pixel(3, 1, RED);
    check_true!(chip.get_pixel(3, 1) == RED, "pixel_red_readback", passed, failed);
    chip.draw_rect(0, 0, 400, 300, BLACK, false);
    chip.draw_line(0, 0, 399, 299, BLACK);
    check_true!(chip.get_pixel(0, 0) == BLACK, "drawing_accepted", passed, failed);
    check_true!(chip.show().is_ok(), "show_completes", passed, failed);
    check_true!(chip.sleep().is_ok(), "sleep_accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
