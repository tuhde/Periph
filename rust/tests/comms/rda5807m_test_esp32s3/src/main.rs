#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::comms::{Rda5807mFull, BAND_WORLD, SPACE_100K};

esp_app_desc!();

const TEST_ADDR: u8 = 0x10;

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

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);

    let mut delay = Delay::new();
    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut fm = match Rda5807mFull::new(i2c, TEST_ADDR, 100.0, 8) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach RDA5807M at 0x{:02X}", TEST_ADDR);
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    let ready_ok = fm.is_ready().unwrap_or(false);
    check_true!(ready_ok, "is_ready", passed, failed);

    let f_ok = fm.frequency().map(|f| f > 99.8 && f < 100.2).unwrap_or(false);
    check_true!(f_ok, "frequency_near_100mhz", passed, failed);

    fm.set_frequency(97.5).ok();
    let f2_ok = fm.frequency().map(|f| f > 97.3 && f < 97.7).unwrap_or(false);
    check_true!(f2_ok, "set_frequency_near_97_5mhz", passed, failed);

    fm.set_volume(10).ok();
    let rssi_ok = fm.signal_strength().map(|r| r <= 127).unwrap_or(false);
    check_true!(rssi_ok, "signal_strength_in_range", passed, failed);

    fm.mute(true).ok();
    fm.mute(false).ok();
    check_true!(fm.is_ready().unwrap_or(false), "mute_unmute_is_ready", passed, failed);

    let seek_ok = fm.seek(true).is_ok();
    check_true!(seek_ok, "seek_returns_ok", passed, failed);

    fm.enable_rds(true).ok();
    check_true!(fm.rds_ready().is_ok(), "rds_ready_ok", passed, failed);

    fm.configure(Some(BAND_WORLD), Some(SPACE_100K), None, None, None, None, None, None).ok();
    check_true!(fm.is_ready().unwrap_or(false), "after_configure_is_ready", passed, failed);

    fm.standby(true, &mut delay).ok();
    fm.standby(false, &mut delay).ok();
    check_true!(fm.is_ready().unwrap_or(false), "after_standby_cycle_is_ready", passed, failed);

    fm.soft_reset(&mut delay).ok();
    check_true!(fm.is_ready().unwrap_or(false), "after_soft_reset_is_ready", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
