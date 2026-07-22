#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::uart::{Config, Uart};
use esp_println::println;
use periph::chips::gnss::{Neo6Minimal, UartBus};

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

// Requires a NEO-6 module wired to UART1 (GPIO17=TX, GPIO18=RX) with a
// clear sky view. Achieving an actual fix needs an outdoor antenna and can
// take up to ~26 s (cold start); this smoke test only requires that
// well-typed values come back, not a fix.
#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let uart = Uart::new(peripherals.UART1, Config::default().with_baudrate(9600))
        .unwrap()
        .with_tx(peripherals.GPIO17)
        .with_rx(peripherals.GPIO18)
        .into_blocking();

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut gps = Neo6Minimal::new(UartBus(uart));

    check_true!(gps.fix() == 0, "fix_starts_at_0", passed, failed);
    check_true!(gps.latitude().is_none(), "latitude_starts_at_none", passed, failed);

    for _ in 0..3000 {
        let _ = gps.update();
    }

    check_true!(gps.fix() <= 2, "fix_is_valid_quality_code", passed, failed);
    if gps.fix() > 0 {
        let lat_ok = gps.latitude().map(|v| v >= -90.0 && v <= 90.0).unwrap_or(false);
        check_true!(lat_ok, "latitude_in_range_once_fixed", passed, failed);
        let lon_ok = gps.longitude().map(|v| v >= -180.0 && v <= 180.0).unwrap_or(false);
        check_true!(lon_ok, "longitude_in_range_once_fixed", passed, failed);
        check_true!(gps.altitude().is_some(), "altitude_populated_once_fixed", passed, failed);
    } else {
        println!("note: no fix acquired during the test window (needs sky view)");
    }

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
