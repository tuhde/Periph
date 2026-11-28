#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::light::Tsl2561Full;

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const ADDR: u8 = 0x39;

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());
    let delay = Delay::new();
    #[allow(non_snake_case)]
    let mut DELAY = Delay::new();

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .expect("i2c config")
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut sensor = Tsl2561Full::new(i2c, ADDR);

    sensor.init(&mut DELAY).expect("init");
    let lx = sensor.read_lux().expect("lux");
    check_true!(lx >= 0.0 && lx <= 40000.0, "read_lux_in_range", passed, failed);
    check_true!(sensor.read_id().expect("id") >> 4 == 1, "read_id_partno", passed, failed);
    check_true!(sensor.is_powered().expect("powered"), "is_powered", passed, failed);
    check_true!(sensor.read_raw().is_ok(), "read_raw", passed, failed);

    sensor.set_gain(16).expect("gain");
    sensor.set_integration_time(101).expect("integ");
    check_true!(sensor.get_gain() == 16 && sensor.get_integration_time() == 101, "gain_integration_roundtrip", passed, failed);
    sensor.set_gain(1).expect("gain");
    sensor.set_integration_time(402).expect("integ");
    let lx = sensor.auto_range(&mut DELAY).expect("auto");
    check_true!(lx >= 0.0, "auto_range_lux", passed, failed);

    sensor.set_thresholds(100, 30000).expect("thr");
    check_true!(sensor.get_thresholds().expect("thr") == (100, 30000), "thresholds_roundtrip", passed, failed);
    check_true!(sensor.configure_interrupt(0, 0).is_ok(), "configure_interrupt", passed, failed);
    check_true!(sensor.clear_interrupt().is_ok(), "clear_interrupt", passed, failed);
    check_true!(sensor.poll_interrupt().is_ok(), "poll_interrupt", passed, failed);
    sensor.power_down().expect("down");
    check_true!(!sensor.is_powered().expect("powered"), "power_down", passed, failed);
    sensor.power_up().expect("up");
    check_true!(sensor.is_powered().expect("powered"), "power_up", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_millis(1000);
    }
}
