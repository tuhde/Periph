//! SHT31 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; ADDR low (address 0x44).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::humidity::{Repeatability, Sht31Full};

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut chip = Sht31Full::new(i2c, 0x44, &mut delay).expect("init");

    esp_println::println!("SHT31 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t > -40.0 && t < 125.0, "temperature range");
    check_true!(rh >= 0.0 && rh <= 100.0, "humidity range");
    check_true!(chip.read_temperature(&mut delay).expect("t") > -40.0, "read_temperature range");
    check_true!(chip.read_humidity(&mut delay).expect("rh") >= 0.0, "read_humidity range");
    check_true!(chip.read_raw(&mut delay).is_ok(), "read_raw ok");

    for level in [Repeatability::Low, Repeatability::Medium, Repeatability::High] {
        chip.set_repeatability(level);
        let (t2, rh2) = chip.read(&mut delay).expect("read level");
        check_true!(t2 > -40.0 && t2 < 125.0 && rh2 >= 0.0 && rh2 <= 100.0, "repeatability read");
    }

    check_true!(chip.read_status().is_ok(), "status readable");
    chip.clear_status().expect("clear_status");
    check_true!(chip.read_status().expect("status") & 0x0010 == 0, "status bit 4 cleared");

    chip.start_periodic(2.0, Repeatability::High, false).expect("start_periodic");
    delay.delay_ms(1200);
    let res = chip.fetch().expect("fetch");
    check_true!(res.is_some(), "periodic fetch");
    chip.stop_periodic(&mut delay).expect("stop_periodic");

    chip.set_heater(true).expect("heater on");
    check_true!(chip.read_status().expect("status") & 0x2000 != 0, "heater bit set");
    chip.set_heater(false).expect("heater off");
    check_true!(chip.read_status().expect("status") & 0x2000 == 0, "heater bit cleared");

    let dp = chip.dew_point(&mut delay).expect("dew_point");
    check_true!(dp > -60.0 && dp < 60.0, "dew point plausible");
    chip.soft_reset(&mut delay).expect("soft_reset");
    check_true!(chip.read(&mut delay).is_ok(), "read after soft reset");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_ms(1000);
    }
}
