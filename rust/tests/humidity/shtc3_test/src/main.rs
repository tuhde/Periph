use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::SHTC3Full;

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
        .unwrap_or(0x70);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }
    let mut chip = SHTC3Full::new(dev, addr);

        chip.init(&mut delay).expect("init");
    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t > -45.0 && t < 130.0, "read_temperature_range", passed, failed);
    check_true!(rh >= 0.0 && rh <= 100.0, "read_humidity_range", passed, failed);
    let h = chip.read_humidity(&mut delay).expect("rh");
    check_true!(h >= 0.0 && h <= 100.0, "read_humidity_single", passed, failed);
    let tc = chip.read_temperature(&mut delay).expect("t");
    check_true!(tc > -45.0 && tc < 130.0, "read_temperature_single", passed, failed);
    let (_tr, _rr) = chip.read_raw(&mut delay).expect("raw");
    check_true!(true, "read_raw", passed, failed);
    check_true!(chip.dew_point(&mut delay).expect("dew") <= tc + 1.0, "dew_point_below_temperature", passed, failed);
    check_true!(chip.read_id(&mut delay).expect("id") & 0x083F == 0x0807, "read_id_product_code", passed, failed);
    chip.set_low_power(true);
    let (_, r) = chip.read(&mut delay).expect("read");
    check_true!(r >= 0.0 && r <= 100.0, "low_power_read", passed, failed);
    chip.set_low_power(false);
    chip.set_sleep_between(false);
    let (_, r) = chip.read(&mut delay).expect("read");
    check_true!(r >= 0.0 && r <= 100.0, "read_without_sleep", passed, failed);
    let (_, r) = chip.read(&mut delay).expect("read");
    check_true!(r >= 0.0 && r <= 100.0, "read_without_sleep_again", passed, failed);
    chip.sleep().expect("sleep");
    chip.wake(&mut delay).expect("wake");
    chip.set_sleep_between(true);
    chip.set_clock_stretching(true);
    let (_, r) = chip.read(&mut delay).expect("read");
    check_true!(r >= 0.0 && r <= 100.0, "read_with_clock_stretching", passed, failed);
    chip.set_clock_stretching(false);
    chip.soft_reset(&mut delay).expect("reset");
    let (_, r) = chip.read(&mut delay).expect("read");
    check_true!(r >= 0.0 && r <= 100.0, "read_after_reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
