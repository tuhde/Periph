use periph::chips::gnss::{Neo6Minimal, UartBus};
use periph::transport::uart_linux::LinuxUart;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

// Requires a NEO-6 module wired to UART with a clear sky view. Achieving an
// actual fix needs an outdoor antenna and can take up to ~26 s (cold start);
// this test only requires that well-typed values come back, not a fix.
fn main() {
    let port = std::env::var("UART_PORT").unwrap_or_else(|_| "/dev/ttyS0".to_string());
    let baud: u32 = std::env::var("UART_BAUD").ok().and_then(|v| v.parse().ok()).unwrap_or(9600);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let uart = LinuxUart::open(&port, baud).expect("open serial port");
    let mut gps = Neo6Minimal::new(UartBus(uart));

    check_true!(gps.fix() == 0, "fix_starts_at_0", passed, failed);
    check_true!(gps.latitude().is_none(), "latitude_starts_at_none", passed, failed);

    for _ in 0..3000 {
        let _ = gps.update();
    }

    check_true!(gps.fix() <= 2, "fix_is_valid_quality_code", passed, failed);
    if gps.fix() > 0 {
        let lat = gps.latitude().unwrap();
        let lon = gps.longitude().unwrap();
        check_true!(lat >= -90.0 && lat <= 90.0, "latitude_in_range_once_fixed", passed, failed);
        check_true!(lon >= -180.0 && lon <= 180.0, "longitude_in_range_once_fixed", passed, failed);
        check_true!(gps.altitude().is_some(), "altitude_populated_once_fixed", passed, failed);
    } else {
        println!("note: no fix acquired during the test window (needs sky view)");
    }

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
