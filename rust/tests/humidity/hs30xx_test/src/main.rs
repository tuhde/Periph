use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::HS30xxFull;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x44);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = HS30xxFull::new(dev, addr);

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t >= -40.0 && t <= 125.0, "temperature range");
    check_true!(rh >= 0.0 && rh <= 100.0, "humidity range");
    check_true!(chip.read_temperature(&mut delay).expect("t") >= -40.0, "read_temperature range");
    check_true!(chip.read_humidity(&mut delay).expect("rh") >= 0.0, "read_humidity range");
    let (rh_raw, t_raw, status) = chip.read_raw(&mut delay).expect("raw");
    check_true!(rh_raw <= 16383 && t_raw <= 16383, "raw ranges");
    check_true!(status == 0, "raw status valid");

    chip.set_measurement_wait_us(60_000).expect("wait");
    check_true!(chip.read_humidity(&mut delay).is_ok(), "read with 60 ms wait");
    chip.set_measurement_wait_us(40_000).expect("wait");

    let dp = chip.dew_point(&mut delay).expect("dew_point");
    check_true!(dp > -60.0 && dp < 60.0, "dew point plausible");

    // NVM access (resolution, ID) needs a power-cycle helper and is not exercised here.

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
