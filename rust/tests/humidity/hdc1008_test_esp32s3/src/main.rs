//! HDC1008 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; ADDR low (address 0x40).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::humidity::HDC1008Full;

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut chip = HDC1008Full::new(i2c, 0x40);

    esp_println::println!("HDC1008 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.init().expect("init");
    check_true!(chip.read_config().expect("config") & 0xFF00 == 0x1000, "config after init");

    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t > -40.0 && t < 125.0, "temperature range");
    check_true!(rh >= 0.0 && rh <= 100.0, "humidity range");
    check_true!(chip.read_humidity(&mut delay).expect("rh") >= 0.0, "read_humidity range");
    check_true!(chip.read_temperature(&mut delay).expect("t") > -40.0, "read_temperature range");
    check_true!(chip.read_raw(&mut delay).is_ok(), "raw ok");

    for (tb, hb) in [(14u8, 14u8), (11, 11), (11, 8), (14, 8)] {
        chip.set_resolution(tb, hb).expect("set_resolution");
        check_true!(chip.read(&mut delay).is_ok(), "read at resolution");
    }
    chip.set_resolution(14, 14).expect("set_resolution");

    chip.set_heater(true).expect("heater on");
    check_true!(chip.is_heater_on().expect("heater"), "heater on");
    chip.set_heater(false).expect("heater off");
    check_true!(!chip.is_heater_on().expect("heater"), "heater off");
    check_true!(!chip.battery_low().expect("battery_low"), "battery ok");
    check_true!(chip.serial_id().expect("serial") != 0, "serial id nonzero");
    check_true!(chip.conversion_time() > 0.005 && chip.conversion_time() < 0.02, "conversion time");
    chip.software_reset(&mut delay).expect("software_reset");
    check_true!(chip.read(&mut delay).is_ok(), "read after software reset");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_ms(1000);
    }
}
