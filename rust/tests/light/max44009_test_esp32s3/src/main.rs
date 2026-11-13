#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::light::MAX44009Full;

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const ADDR: u8 = 0x4A;

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
    let mut sensor = MAX44009Full::new(i2c, ADDR);

    sensor.init(&mut DELAY).expect("init");
    let lx = sensor.lux().expect("lux");
    check_true!(lx >= 0.0 && lx <= 188006.4, "lux_in_range", passed, failed);
    let (e, m, over) = sensor.read_raw().expect("raw");
    check_true!(e <= 15 && over == (e == 15), "read_raw_ranges", passed, failed);

    sensor.set_continuous(true).expect("cont");
    check_true!(sensor.get_config().expect("cfg").continuous, "continuous_on", passed, failed);
    sensor.set_continuous(false).expect("cont");
    check_true!(!sensor.get_config().expect("cfg").continuous, "continuous_off", passed, failed);
    sensor.set_manual(100.0, false).expect("manual");
    let cfg = sensor.get_config().expect("cfg");
    check_true!(cfg.manual && cfg.integration_ms == 100.0 && !cfg.divide_by_8, "manual_100ms", passed, failed);
    sensor.set_manual(25.0, true).expect("manual");
    let cfg = sensor.get_config().expect("cfg");
    check_true!(cfg.integration_ms == 25.0 && cfg.divide_by_8, "manual_25ms_divide", passed, failed);
    sensor.set_automatic().expect("auto");
    check_true!(!sensor.get_config().expect("cfg").manual, "automatic", passed, failed);

    sensor.set_thresholds(50.0, 500.0).expect("thr");
    let (lo, hi) = sensor.get_thresholds().expect("thr");
    check_true!((lo - 50.0).abs() / 50.0 < 0.2 && (hi - 500.0).abs() / 500.0 < 0.2, "thresholds_roundtrip", passed, failed);
    sensor.set_threshold_delay(2.0).expect("delay");
    check_true!((sensor.get_threshold_delay().expect("delay") - 2.0).abs() < 0.05, "threshold_delay", passed, failed);
    sensor.enable_interrupt().expect("en");
    sensor.disable_interrupt().expect("dis");
    check_true!(sensor.poll_interrupt().expect("status") == 0, "poll_interrupt_cleared", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_millis(1000);
    }
}
