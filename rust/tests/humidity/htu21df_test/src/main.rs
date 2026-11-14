use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::HTU21DFFull;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = HTU21DFFull::new(dev, addr);

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.init(&mut delay).expect("init");
    check_true!(chip.read_user_register().expect("user register") & 0x83 == 0x02, "user register after reset");

    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t > -40.0 && t < 125.0, "temperature range");
    check_true!(rh >= -6.0 && rh <= 119.0, "humidity range");
    check_true!(chip.read_humidity(&mut delay).expect("rh") >= -6.0, "read_humidity range");
    check_true!(chip.read_temperature(&mut delay).expect("t") > -40.0, "read_temperature range");
    check_true!(chip.read_humidity_compensated(&mut delay).expect("comp") >= -6.0, "compensated humidity range");
    check_true!(chip.read_raw(&mut delay).is_ok(), "raw ok");

    for res in 0u8..4 {
        chip.set_resolution(res).expect("set_resolution");
        check_true!(chip.get_resolution().expect("get_resolution") == res, "resolution roundtrip");
        check_true!(chip.read(&mut delay).is_ok(), "read at resolution");
    }
    chip.set_resolution(0).expect("set_resolution");

    chip.set_heater(true).expect("heater on");
    check_true!(chip.heater().expect("heater"), "heater on");
    chip.set_heater(false).expect("heater off");
    check_true!(!chip.heater().expect("heater"), "heater off");
    check_true!(!chip.battery_low().expect("battery_low"), "battery ok");

    let dp = chip.dew_point(&mut delay).expect("dew_point");
    check_true!(dp > -60.0 && dp < 60.0, "dew point plausible");
    chip.soft_reset(&mut delay).expect("soft_reset");
    check_true!(chip.read(&mut delay).is_ok(), "read after soft reset");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
