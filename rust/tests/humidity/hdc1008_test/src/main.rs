use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::HDC1008Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = HDC1008Full::new(dev, addr);

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.init().expect("init");
    check_true!(chip.read_config().expect("config") & 0xFF00 == 0x1000, "config after init");

    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t > -40.0 && t < 125.0, "temperature range");
    check_true!(rh >= 0.0 && rh <= 100.0, "humidity range");
    check_true!(chip.read_humidity(&mut delay).expect("rh") >= 0.0, "read_humidity range");
    check_true!(chip.read_temperature(&mut delay).expect("t") > -40.0, "read_temperature range");
    check_true!(chip.read_raw(&mut delay).is_ok(), "raw ok");

    for (tb, hb) in [(14u8, 14u8), (11, 11), (11, 8), (14, 8)] {
        chip.set_resolution(tb, hb).expect("set_resolution");
        check_true!(chip.read(&mut delay).is_ok(), "read at resolution");
    }
    chip.set_resolution(14, 14).expect("set_resolution");

    chip.set_heater(true).expect("heater on");
    check_true!(chip.is_heater_on().expect("heater"), "heater on");
    chip.set_heater(false).expect("heater off");
    check_true!(!chip.is_heater_on().expect("heater"), "heater off");
    check_true!(!chip.battery_low().expect("battery_low"), "battery ok");
    check_true!(chip.serial_id().expect("serial") != 0, "serial id nonzero");
    check_true!(chip.conversion_time() > 0.005 && chip.conversion_time() < 0.02, "conversion time");
    chip.software_reset(&mut delay).expect("software_reset");
    check_true!(chip.read(&mut delay).is_ok(), "read after software reset");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
