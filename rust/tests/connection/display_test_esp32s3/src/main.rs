#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::{Input, InputConfig, Level, Output, OutputConfig};
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::connection::display::{DisplayConnection, DisplayError, WireMode};

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

    let mut c = DisplayConnection::new(device, dc, Some(res), Some(busy), Delay::new(), WireMode::Simplex)
        .unwrap();

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_true!(c.reset().is_ok(), "reset_accepted", passed, failed);
    check_true!(c.send_command(0x12, &[]).is_ok(), "command_accepted", passed, failed);
    check_true!(
        c.send_command(0x04, &[0x01, 0x02]).is_ok(),
        "command_with_parameters_accepted",
        passed,
        failed
    );
    check_true!(c.send_data(&[0xFF; 16]).is_ok(), "data_accepted", passed, failed);
    check_true!(c.busy_level().is_ok(), "busy_level_readable", passed, failed);
    check_true!(
        c.wait_busy(false, Some(50)).is_ok(),
        "wait_busy_returns_within_timeout",
        passed,
        failed
    );
    let mut buf = [0u8; 1];
    check_true!(
        matches!(c.read_data(&mut buf), Err(DisplayError::ReadUnavailable)),
        "read_data_unavailable_in_simplex",
        passed,
        failed
    );

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
