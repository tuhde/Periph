//! BH1750 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; address 0x23.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::light::{BH1750Full, BH1750Mode as Mode};

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
    let mut chip = BH1750Full::new(i2c, 0x23);

    esp_println::println!("BH1750 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.init().expect("init");
    check_true!(chip.read(&mut delay).map(|l| l >= 0.0 && l <= 120000.0).expect("read"), "read_range");
    check_true!(chip.read_raw(&mut delay).expect("raw") as u32 <= 65535, "read_raw_range");
    for (name, mode) in [("h", Mode::H), ("h2", Mode::H2), ("l", Mode::L)] {
        let l = chip.measure_once(mode, &mut delay).expect("measure");
        check_true!(l >= 0.0 && l <= 120000.0, name);
    }
    check_true!((chip.lux_per_count(Mode::H) - 1.0 / 1.2).abs() < 1e-5, "lux_per_count_default");
    check_true!((chip.measurement_time(Mode::H) - 0.180).abs() < 1e-5, "measurement_time_default");
    chip.start_continuous(Mode::H).expect("continuous");
    sleep_ms!(200);
    let l = chip.read_continuous().expect("continuous");
    check_true!(l >= 0.0 && l <= 120000.0, "read_continuous_range");
    chip.set_measurement_time(138).expect("mtreg");
    check_true!(chip.get_measurement_time_register() == 138, "mtreg_roundtrip");
    check_true!((chip.measurement_time(Mode::H) - 0.360).abs() < 1e-5, "measurement_time_scaled");
    let l = chip.measure_once(Mode::H, &mut delay).expect("measure");
    check_true!(l >= 0.0 && l <= 120000.0, "read_with_mtreg");
    chip.set_sensitivity(1.0).expect("sensitivity");
    check_true!(chip.get_measurement_time_register() == 69, "sensitivity_default");
    chip.power_down().expect("power down");
    chip.power_on().expect("power on");
    chip.reset().expect("reset");
    let l = chip.measure_once(Mode::H, &mut delay).expect("measure");
    check_true!(l >= 0.0 && l <= 120000.0, "read_after_reset");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        sleep_ms!(1000);
    }
}
