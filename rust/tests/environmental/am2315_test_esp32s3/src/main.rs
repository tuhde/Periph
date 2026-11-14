//! AM2315 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; ADDR low (address 0x5C).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::environmental::AM2315Full;

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut chip = AM2315Full::new(i2c, 0x5C);

    esp_println::println!("AM2315 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    let (model, _version, _id) = chip.read_device_info(&mut delay).expect("device info");
    check_true!(model != 0xFFFF, "device info model");

    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t >= -40.0 && t <= 125.0, "temperature range");
    check_true!(rh >= 0.0 && rh <= 100.0, "humidity range");
    check_true!(chip.read_humidity(&mut delay).expect("rh") <= 100.0, "read_humidity range");
    check_true!(chip.read_temperature(&mut delay).expect("t") >= -40.0, "read_temperature range");
    let (rr, rt) = chip.read_raw(&mut delay).expect("raw");
    check_true!(rr <= 1000 && (rt & 0x7FFF) <= 1250, "raw ranges");
    let (ft, fh) = chip.read_fresh(&mut delay).expect("fresh");
    check_true!(ft >= -40.0 && fh <= 100.0, "read_fresh range");
    let mut regs = [0u8; 4];
    check_true!(chip.read_registers(0x00, &mut regs, &mut delay).is_ok(), "read_registers ok");

    chip.write_user_registers(0x1234, 0xABCD, &mut delay).expect("write user");
    check_true!(chip.read_user_registers(&mut delay).expect("read user") == (0x1234, 0xABCD), "user registers round trip");
    chip.write_user_registers(0, 0, &mut delay).expect("clear user");
    chip.wake(&mut delay);

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_ms(1000);
    }
}
