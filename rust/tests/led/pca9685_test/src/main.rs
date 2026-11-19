use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::led::pca9685::{PCA9685Full, PCA9685Minimal, CHANNELS, OFF_LOW};

const DEFAULT_ADDR: u8 = 0x40;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(DEFAULT_ADDR);

    let mut passed = 0i32;
    let mut failed = 0i32;
    let mut delay = Delay;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pwm = PCA9685Minimal::new(dev, addr);
    pwm.init(&mut delay).expect("init PCA9685");
    check_true!(CHANNELS == 16, "constants", passed, failed);
    check_true!(pwm.set_duty(0, 0.5).is_ok() && pwm.set_duty(15, 0.0).is_ok() && pwm.set_duty(1, 1.0).is_ok(), "set_duty", passed, failed);
    check_true!(pwm.set_duty(16, 0.5).is_err() && pwm.set_duty(0, 1.5).is_err(), "set_duty_range", passed, failed);

    let mut chip = PCA9685Full::new(pwm.release(), addr);
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
    if failed != 0 {
        std::process::exit(1);
    }
}
