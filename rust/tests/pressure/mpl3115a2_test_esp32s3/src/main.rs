//! MPL3115A2 hardware-in-loop test for ESP32-S3.
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
use periph::chips::pressure::mpl3115a2;
use periph::chips::pressure::MPL3115A2Full;

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
    let mut chip = MPL3115A2Full::new(i2c, 0x60);

    esp_println::println!("MPL3115A2 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.init(&mut delay).expect("init");
    check_true!(chip.who_am_i().expect("who_am_i") == 0xC4, "who_am_i");
    let (p, t) = chip.read(&mut delay).expect("read");
    check_true!(p > 20000.0 && p < 110000.0, "read_pressure_range");
    check_true!(t > -40.0 && t < 85.0, "read_temperature_range");
    let p1 = chip.read_pressure(&mut delay).expect("p");
    check_true!(p1 > 20000.0 && p1 < 110000.0, "read_pressure_single");
    let t1 = chip.read_temperature(&mut delay).expect("t");
    check_true!(t1 > -40.0 && t1 < 85.0, "read_temperature_single");
    for ratio in [1u8, 8, 128] {
        chip.set_oversample(ratio).expect("oversample");
        check_true!(chip.get_oversample() == ratio, "oversample_roundtrip");
        let r = chip.read_pressure(&mut delay).expect("read");
        check_true!(r > 20000.0 && r < 110000.0, "read_at_osr");
    }
    chip.set_oversample(16).expect("oversample");
    chip.set_sea_level_pressure(101325.0).expect("sea level");
    let alt = chip.read_altitude(&mut delay).expect("altitude");
    check_true!(alt > -500.0 && alt < 9000.0, "read_altitude_range");
    chip.set_mode(false).expect("mode");
    chip.start_continuous(0).expect("start");
    check_true!(chip.is_active().expect("active"), "is_active");
    let (v, _) = chip.read_continuous(&mut delay).expect("continuous");
    check_true!(v > 20000.0 && v < 110000.0, "read_continuous");
    chip.stop_continuous().expect("stop");
    check_true!(!chip.is_active().expect("active"), "is_standby");
    chip.fifo_setup(mpl3115a2::FIFO_CIRCULAR, 4).expect("fifo");
    chip.start_continuous(0).expect("start");
    sleep_ms!(5000);
    let (_, _, count) = chip.fifo_status().expect("fifo_status");
    check_true!(count >= 2, "fifo_collects");
    let mut samples = [(0.0f32, 0.0f32); 32];
    check_true!(chip.fifo_read(&mut samples).expect("fifo_read") >= 2, "fifo_read_count");
    chip.stop_continuous().expect("stop");
    chip.fifo_setup(mpl3115a2::FIFO_OFF, 0).expect("fifo off");
    chip.enable_interrupt(mpl3115a2::SOURCE_DRDY, 1).expect("enable");
    chip.disable_interrupt(mpl3115a2::SOURCE_DRDY).expect("disable");
    chip.reset(&mut delay).expect("reset");
    let r = chip.read_pressure(&mut delay).expect("read");
    check_true!(r > 20000.0 && r < 110000.0, "read_after_reset");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        sleep_ms!(1000);
    }
}
