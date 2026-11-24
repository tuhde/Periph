//! Si1132 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; address 0x60.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::light::Si1132Full;

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
    let mut chip = Si1132Full::new(i2c, 0x60);

    esp_println::println!("Si1132 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }


    chip.init(&mut delay).expect("init");
    let r = chip.read(&mut delay).expect("read");
    check_true!(r.uv_index >= 0.0 && r.uv_index < 20.0, "read_uv_index_range");
    check_true!(chip.read_uv_index(&mut delay).expect("uv") < 20.0, "read_uv_index_single");
    check_true!(chip.read_visible(&mut delay).is_ok(), "read_visible_single");
    check_true!(chip.read_infrared(&mut delay).is_ok(), "read_infrared_single");
    check_true!(chip.read_raw().is_ok(), "read_raw");
    check_true!(chip.read_temperature_raw(&mut delay).is_ok(), "temperature_raw");
    check_true!(chip.read_vdd_raw(&mut delay).is_ok(), "vdd_raw");
    chip.set_gain(&mut delay, 2, 2).expect("gain");
    check_true!(chip.read_visible(&mut delay).is_ok(), "gain_read");
    chip.set_gain(&mut delay, 0, 0).expect("gain");
    chip.set_high_range(&mut delay, true, true).expect("range");
    check_true!(chip.read_visible(&mut delay).is_ok(), "high_range_read");
    chip.set_high_range(&mut delay, false, false).expect("range");
    chip.set_channels(&mut delay, true, false, false, false).expect("channels");
    check_true!(chip.read(&mut delay).is_ok(), "uv_only_read");
    chip.set_channels(&mut delay, true, true, true, false).expect("channels");
    check_true!(chip.set_channels(&mut delay, false, false, false, false).is_err(), "channels_invalid");
    check_true!(chip.read_calibration(&mut delay).is_ok(), "read_calibration");
    chip.force(&mut delay).expect("force");
    sleep_ms!(50);
    check_true!(chip.measurement_ready().expect("ready"), "measurement_ready");
    check_true!(chip.read_response().expect("response").1 == 0, "response_no_error");
    let _ = chip.poll_interrupt();
    chip.start_autonomous(&mut delay, 0.1).expect("autonomous");
    sleep_ms!(500);
    check_true!(chip.measurement_ready().expect("ready"), "autonomous_ready");
    chip.pause(&mut delay).expect("pause");
    chip.enable_interrupt().expect("enable interrupt");
    chip.disable_interrupt().expect("disable interrupt");
    chip.reset(&mut delay).expect("reset");
    check_true!(chip.read(&mut delay).is_ok(), "read_after_reset");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        sleep_ms!(1000);
    }
}
