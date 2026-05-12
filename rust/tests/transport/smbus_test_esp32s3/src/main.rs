#![no_std]
#![no_main]

use embedded_hal::i2c::I2c;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::i2c::master::{Config, I2c as EspI2c};
use esp_println::println;
use periph::transport::smbus::{SmBusError, SmBusTransport};

esp_app_desc!();

const TEST_ADDR: u8 = 0x40;

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

    let mut passed = 0i32;
    let mut failed = 0i32;

    // --- address validation ---

    let i2c = EspI2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);

    let result = SmBusTransport::new(i2c, 0x07, false);
    check_true!(
        matches!(result, Err(SmBusError::InvalidAddress)),
        "addr_0x07_rejected",
        passed,
        failed
    );

    // I2C was consumed; re-initialise for remaining tests.
    // (esp-hal I2C is re-initialised from peripherals after construction failure)
    let i2c = EspI2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);

    // --- basic I/O without PEC ---

    let mut transport = match SmBusTransport::new(i2c, TEST_ADDR, false) {
        Ok(t) => t,
        Err(_) => {
            println!("FAIL init: could not create SmBusTransport at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    let mut buf = [0u8; 1];
    check_true!(transport.read(TEST_ADDR, &mut buf).is_ok(), "read_ok", passed, failed);
    check_true!(transport.write(TEST_ADDR, &[0x00]).is_ok(), "write_ok", passed, failed);
    check_true!(
        transport.write_read(TEST_ADDR, &[0x00], &mut buf).is_ok(),
        "write_read_ok",
        passed,
        failed
    );

    // --- write with PEC enabled ---

    let i2c = EspI2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);

    let mut transport_pec = SmBusTransport::new(i2c, TEST_ADDR, true).unwrap();
    check_true!(
        transport_pec.write(TEST_ADDR, &[0x00]).is_ok(),
        "write_with_pec_ok",
        passed,
        failed
    );

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
