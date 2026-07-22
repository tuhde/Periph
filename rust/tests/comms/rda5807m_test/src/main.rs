use linux_embedded_hal::Delay;
use linux_embedded_hal::I2cdev;
use periph::chips::comms::{Rda5807mFull, BAND_WORLD, SPACE_100K};
use std::thread::sleep;
use std::time::Duration;

// FM_READY deasserts on any register write and takes ~20 ms to settle back;
// not documented in the datasheet, measured on real hardware.
const SETTLE_MS: u64 = 30;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}", $label);
            $failed += 1;
        }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x10);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut fm = Rda5807mFull::new(dev, addr, 100.0, 8).expect("init RDA5807M");

    let mut passed = 0i32;
    let mut failed = 0i32;

    sleep(Duration::from_millis(SETTLE_MS));
    check_true!(fm.is_ready().unwrap(), "is_ready", passed, failed);

    let f = fm.frequency().unwrap();
    check_true!(f > 99.8 && f < 100.2, "frequency_near_100mhz", passed, failed);

    fm.set_frequency(97.5).unwrap();
    let f = fm.frequency().unwrap();
    check_true!(f > 97.3 && f < 97.7, "set_frequency_near_97_5mhz", passed, failed);

    fm.set_volume(10).unwrap();
    let rssi = fm.signal_strength().unwrap();
    check_true!(rssi <= 127, "signal_strength_in_range", passed, failed);

    fm.mute(true).unwrap();
    fm.mute(false).unwrap();
    sleep(Duration::from_millis(SETTLE_MS));
    check_true!(fm.is_ready().unwrap(), "mute_unmute_is_ready", passed, failed);

    let seek_result = fm.seek(true);
    check_true!(seek_result.is_ok(), "seek_returns_ok", passed, failed);

    fm.enable_rds(true).unwrap();
    check_true!(fm.rds_ready().is_ok(), "rds_ready_ok", passed, failed);

    fm.configure(Some(BAND_WORLD), Some(SPACE_100K), None, None, None, None, None, None).unwrap();
    sleep(Duration::from_millis(SETTLE_MS));
    check_true!(fm.is_ready().unwrap(), "after_configure_is_ready", passed, failed);

    fm.standby(true, &mut delay).unwrap();
    sleep(Duration::from_millis(10));
    fm.standby(false, &mut delay).unwrap();
    check_true!(fm.is_ready().unwrap(), "after_standby_cycle_is_ready", passed, failed);

    fm.soft_reset(&mut delay).unwrap();
    check_true!(fm.is_ready().unwrap(), "after_soft_reset_is_ready", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
