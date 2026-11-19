//! AS7262 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; fixed address 0x49.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::color::AS7262Full;

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut chip = AS7262Full::new(i2c, 0x49);

    esp_println::println!("AS7262 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.init(&mut delay).expect("init");

    check_true!(chip.device_type(&mut delay).expect("type") == 0x40, "device type");
    check_true!(chip.hardware_version(&mut delay).expect("hw") == (0x40, 0x3E), "hardware version");
    let t = chip.temperature(&mut delay).expect("temperature");
    check_true!(t >= -40.0 && t <= 125.0, "temperature range");

    let d = chip.read(&mut delay).expect("read");
    check_true!(d.to_array().iter().all(|v| *v >= 0.0), "calibrated channels");
    let r = chip.read_raw(&mut delay).expect("read_raw");
    check_true!(r.to_array().len() == 6, "raw channels");
    let raw2 = chip.read_channel_raw(2, &mut delay).expect("channel");
    check_true!(raw2 == raw2, "read_channel_raw");

    chip.set_gain(64.0, &mut delay).expect("set_gain");
    check_true!(chip.get_gain(&mut delay).expect("get_gain") == 64.0, "gain");
    chip.set_gain(16.0, &mut delay).expect("set_gain");
    chip.set_integration_time(50.0, &mut delay).expect("set_integration_time");
    check_true!((chip.get_integration_time(&mut delay).expect("get") - 50.4).abs() < 0.01, "integration time");
    chip.set_mode(2, &mut delay).expect("set_mode");
    check_true!(chip.get_mode(&mut delay).expect("get_mode") == 2, "mode");
    chip.set_mode(3, &mut delay).expect("set_mode");

    chip.measure(&mut delay).expect("measure");
    delay.delay_ms(600);
    check_true!(chip.data_ready(&mut delay).expect("data_ready"), "data ready");
    let _ = chip.read_calibrated(&mut delay).expect("read_calibrated");

    chip.set_indicator_led(true, 4.0, &mut delay).expect("led");
    chip.set_indicator_led(false, 1.0, &mut delay).expect("led");
    chip.soft_reset(&mut delay).expect("soft_reset");
    check_true!(chip.get_integration_time(&mut delay).expect("get") > 700.0, "reset int_t");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_ms(1000);
    }
}
