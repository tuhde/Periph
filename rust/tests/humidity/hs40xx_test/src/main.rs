use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::HS40xxFull;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x54);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = HS40xxFull::new(dev, addr);

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
    let (rh_raw, t_raw) = chip.read_raw(&mut delay).expect("raw");
    check_true!(rh_raw <= 16383 && t_raw <= 16383, "raw ranges");
    check_true!(chip.read_id(&mut delay).expect("id") != 0, "id nonzero");

    chip.set_resolution(Some(12), Some(10), &mut delay).expect("set_resolution");
    check_true!(chip.get_resolution(&mut delay).expect("get_resolution") == (12, 10), "resolution readback");
    chip.set_resolution(Some(14), Some(14), &mut delay).expect("set_resolution");

    chip.start_periodic(0.5, &mut delay).expect("start_periodic");
    std::thread::sleep(std::time::Duration::from_millis(1000));
    let p = chip.read_periodic(&mut delay).expect("read_periodic");
    check_true!(p.humidity_pct >= 0.0 && p.humidity_pct <= 100.0, "periodic humidity");
    chip.stop_periodic().expect("stop_periodic");
    chip.soft_reset(&mut delay).expect("soft_reset");

    let dp = chip.dew_point(&mut delay).expect("dew_point");
    check_true!(dp > -60.0 && dp < 60.0, "dew point plausible");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
