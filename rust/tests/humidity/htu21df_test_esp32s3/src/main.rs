//! HTU21DF hardware-in-loop test for ESP32-S3.
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
use periph::chips::humidity::HTU21DFFull;

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut chip = HTU21DFFull::new(i2c, 0x40);

    esp_println::println!("HTU21DF test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.init(&mut delay).expect("init");
    check_true!(chip.read_user_register().expect("user register") & 0x83 == 0x02, "user register after reset");

    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t > -40.0 && t < 125.0, "temperature range");
    check_true!(rh >= -6.0 && rh <= 119.0, "humidity range");
    check_true!(chip.read_humidity(&mut delay).expect("rh") >= -6.0, "read_humidity range");
    check_true!(chip.read_temperature(&mut delay).expect("t") > -40.0, "read_temperature range");
    check_true!(chip.read_humidity_compensated(&mut delay).expect("comp") >= -6.0, "compensated humidity range");
    check_true!(chip.read_raw(&mut delay).is_ok(), "raw ok");

    for res in 0u8..4 {
        chip.set_resolution(res).expect("set_resolution");
        check_true!(chip.get_resolution().expect("get_resolution") == res, "resolution roundtrip");
        check_true!(chip.read(&mut delay).is_ok(), "read at resolution");
    }
    chip.set_resolution(0).expect("set_resolution");

    chip.set_heater(true).expect("heater on");
    check_true!(chip.heater().expect("heater"), "heater on");
    chip.set_heater(false).expect("heater off");
    check_true!(!chip.heater().expect("heater"), "heater off");
    check_true!(!chip.battery_low().expect("battery_low"), "battery ok");

    let dp = chip.dew_point(&mut delay).expect("dew_point");
    check_true!(dp > -60.0 && dp < 60.0, "dew point plausible");
    chip.soft_reset(&mut delay).expect("soft_reset");
    check_true!(chip.read(&mut delay).is_ok(), "read after soft reset");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_ms(1000);
    }
}
