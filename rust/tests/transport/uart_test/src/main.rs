use embedded_io::{Read, Write};
use periph::transport::uart_linux::LinuxUart;

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

// Assumes a loopback jumper bridging TXD and RXD on the port under test.
fn main() {
    let port = std::env::var("UART_PORT").unwrap_or_else(|_| "/dev/ttyS0".to_string());
    let baud  = std::env::var("UART_BAUD")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(9600u32);

    let mut uart = LinuxUart::open(&port, baud).expect("open serial port");

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_true!(uart.write_all(&[0x42]).is_ok(), "write_ok", passed, failed);

    let mut buf = [0u8; 1];
    check_true!(uart.read(&mut buf).is_ok(), "read_ok", passed, failed);
    check_true!(buf[0] == 0x42, "loopback_byte_matches", passed, failed);

    let mut resp = [0u8; 2];
    let _ = uart.write_all(&[0xA5, 0x5A]);
    let _ = uart.read(&mut resp);
    check_true!(
        resp[0] == 0xA5 && resp[1] == 0x5A,
        "write_read_loopback_matches",
        passed,
        failed
    );

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
