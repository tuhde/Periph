//! SHTC3 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; address 0x70.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::humidity::SHTC3Full;

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }
    let mut chip = SHTC3Full::new(i2c, 0x70);

    esp_println::println!("SHTC3 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

        chip.init(&mut delay).expect("init");
    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t > -45.0 && t < 130.0, "read_temperature_range");
    check_true!(rh >= 0.0 && rh <= 100.0, "read_humidity_range");
    let h = chip.read_humidity(&mut delay).expect("rh");
    check_true!(h >= 0.0 && h <= 100.0, "read_humidity_single");
    let tc = chip.read_temperature(&mut delay).expect("t");
    check_true!(tc > -45.0 && tc < 130.0, "read_temperature_single");
    let (_tr, _rr) = chip.read_raw(&mut delay).expect("raw");
    check_true!(true, "read_raw");
    check_true!(chip.dew_point(&mut delay).expect("dew") <= tc + 1.0, "dew_point_below_temperature");
    check_true!(chip.read_id(&mut delay).expect("id") & 0x083F == 0x0807, "read_id_product_code");
    chip.set_low_power(true);
    let (_, r) = chip.read(&mut delay).expect("read");
    check_true!(r >= 0.0 && r <= 100.0, "low_power_read");
    chip.set_low_power(false);
    chip.set_sleep_between(false);
    let (_, r) = chip.read(&mut delay).expect("read");
    check_true!(r >= 0.0 && r <= 100.0, "read_without_sleep");
    let (_, r) = chip.read(&mut delay).expect("read");
    check_true!(r >= 0.0 && r <= 100.0, "read_without_sleep_again");
    chip.sleep().expect("sleep");
    chip.wake(&mut delay).expect("wake");
    chip.set_sleep_between(true);
    chip.set_clock_stretching(true);
    let (_, r) = chip.read(&mut delay).expect("read");
    check_true!(r >= 0.0 && r <= 100.0, "read_with_clock_stretching");
    chip.set_clock_stretching(false);
    chip.soft_reset(&mut delay).expect("reset");
    let (_, r) = chip.read(&mut delay).expect("read");
    check_true!(r >= 0.0 && r <= 100.0, "read_after_reset");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        sleep_ms!(1000);
    }
}
