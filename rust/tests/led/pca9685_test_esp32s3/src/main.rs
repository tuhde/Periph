#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::led::pca9685::{PCA9685Full, PCA9685Minimal, CHANNELS, OFF_LOW};

esp_app_desc!();

const TEST_ADDR: u8 = 0x40;

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

    let mut pwm = PCA9685Minimal::new(i2c, TEST_ADDR);
    if pwm.init(&mut delay).is_err() {
        println!("FAIL init: could not reach PCA9685 at 0x{:02X}", TEST_ADDR);
        println!("===DONE: 0 passed, 1 failed===");
        loop {}
    }
    check_true!(CHANNELS == 16, "constants", passed, failed);
    check_true!(pwm.set_duty(0, 0.5).is_ok() && pwm.set_duty(15, 0.0).is_ok() && pwm.set_duty(1, 1.0).is_ok(), "set_duty", passed, failed);
    check_true!(pwm.set_duty(16, 0.5).is_err() && pwm.set_duty(0, 1.5).is_err(), "set_duty_range", passed, failed);

    let mut chip = PCA9685Full::new(pwm.release(), TEST_ADDR);
    check_true!(chip.set_pwm(2, 409, 1228).is_ok() && chip.set_pwm(2, 5, 5).is_err(), "set_pwm", passed, failed);
    check_true!(chip.get_pwm(2).unwrap_or((0, 0)) == (409, 1228), "get_pwm", passed, failed);
    check_true!(chip.set_full_on(3).is_ok() && chip.set_full_off(3).is_ok(), "full_on_off", passed, failed);
    check_true!(chip.set_all_duty(0.1).is_ok() && chip.set_all_pwm(0, 2048).is_ok() && chip.set_all_duty(0.0).is_ok(), "all", passed, failed);
    check_true!(chip.set_frequency(50.0, 25_000_000.0, &mut delay).is_ok(), "set_frequency", passed, failed);
    let hz = chip.get_frequency(25_000_000.0).unwrap_or(0.0);
    check_true!(hz > 49.9 && hz < 50.1, "get_frequency", passed, failed);
    check_true!(chip.set_pulse_width(15, 0.0015).is_ok(), "pulse_width", passed, failed);
    check_true!(chip.sleep().is_ok() && chip.wake(&mut delay).is_ok() && chip.restart(&mut delay).is_ok(), "sleep_wake", passed, failed);
    check_true!(chip.set_output_mode(false, false, OFF_LOW).is_ok() && chip.set_update_on_ack(false).is_ok(), "output_mode", passed, failed);
    check_true!(chip.set_allcall(false, 0x70).is_ok(), "allcall", passed, failed);
    check_true!(chip.set_subaddress(1, 0x71, false).is_ok(), "subaddress", passed, failed);
    let (m1, m2) = chip.read_mode().unwrap_or((0xFF, 0));
    check_true!(m1 & 0x10 == 0 && m2 & 0x04 == 0x04, "read_mode", passed, failed);
    check_true!(chip.reset().is_ok(), "reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
