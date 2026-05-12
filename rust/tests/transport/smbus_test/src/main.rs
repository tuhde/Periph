use linux_embedded_hal::I2cdev;
use periph::transport::smbus::{SmBusError, SmBusTransport};

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

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- address validation (no bus needed) ---

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let result = SmBusTransport::new(dev, 0x07, false);
    check_true!(
        matches!(result, Err(SmBusError::InvalidAddress)),
        "addr_0x07_rejected",
        passed,
        failed
    );

    // new() consumed dev on error; open a fresh one for 0x78 check
    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let result = SmBusTransport::new(dev, 0x78, false);
    check_true!(
        matches!(result, Err(SmBusError::InvalidAddress)),
        "addr_0x78_rejected",
        passed,
        failed
    );

    // --- basic I/O without PEC ---

    use embedded_hal::i2c::I2c;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut transport = SmBusTransport::new(dev, addr, false).expect("init SmBusTransport");

    let mut buf = [0u8; 1];
    let read_ok = transport.read(addr, &mut buf).is_ok();
    check_true!(read_ok, "read_ok", passed, failed);

    let write_ok = transport.write(addr, &[0x00]).is_ok();
    check_true!(write_ok, "write_ok", passed, failed);

    let wr_ok = transport.write_read(addr, &[0x00], &mut buf).is_ok();
    check_true!(wr_ok, "write_read_ok", passed, failed);

    // --- write with PEC enabled ---

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut transport_pec = SmBusTransport::new(dev, addr, true).expect("init SmBusTransport PEC");
    let write_pec_ok = transport_pec.write(addr, &[0x00]).is_ok();
    check_true!(write_pec_ok, "write_with_pec_ok", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
