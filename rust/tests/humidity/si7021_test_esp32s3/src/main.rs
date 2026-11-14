//! Si7021 hardware-in-loop test for ESP32-S3.
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
use periph::chips::humidity::Si7021Full;

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut chip = Si7021Full::new(i2c, 0x40);

    esp_println::println!("Si7021 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.software_reset(&mut delay).expect("software_reset");
    check_true!(chip.device_type().expect("device_type") == 0x15, "device type");
    check_true!(chip.serial_number().expect("serial_number") > 0, "serial number");
    check_true!(chip.read_user_register().expect("user register") & 0xBF == 0x3A, "user register after reset");

    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t > -40.0 && t < 125.0, "temperature range");
    check_true!(rh >= 0.0 && rh <= 100.0, "humidity range");
    check_true!(chip.read_humidity(&mut delay).expect("rh") >= 0.0, "read_humidity range");
    check_true!(chip.read_temperature(&mut delay).expect("t") > -40.0, "read_temperature range");
    check_true!(chip.read_temperature_previous().expect("prev") > -40.0, "read_temperature_previous range");
    check_true!(chip.read_raw_humidity(&mut delay).is_ok(), "raw humidity ok");
    check_true!(chip.read_raw_temperature(&mut delay).is_ok(), "raw temperature ok");

    for (rb, tb) in [(12u8, 14u8), (8, 12), (10, 13), (11, 11)] {
        chip.set_resolution(rb, tb).expect("set_resolution");
        check_true!(chip.get_resolution().expect("get_resolution") == (rb, tb), "resolution roundtrip");
        check_true!(chip.read(&mut delay).is_ok(), "read at resolution");
    }
    chip.set_resolution(12, 14).expect("set_resolution");
    let ct = chip.conversion_time().expect("conversion_time");
    check_true!(ct > 0.005 && ct < 0.03, "conversion time");

    chip.set_heater(true).expect("heater on");
    check_true!(chip.is_heater_on().expect("is_heater_on"), "heater on");
    chip.set_heater(false).expect("heater off");
    check_true!(!chip.is_heater_on().expect("is_heater_on"), "heater off");
    check_true!(!chip.vdd_low().expect("vdd_low"), "vdd ok");
    check_true!(chip.firmware_revision().is_ok(), "firmware readable");

    let dp = chip.dew_point(&mut delay).expect("dew_point");
    check_true!(dp > -60.0 && dp < 60.0, "dew point plausible");
    chip.software_reset(&mut delay).expect("software_reset");
    check_true!(chip.read(&mut delay).is_ok(), "read after software reset");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_ms(1000);
    }
}
