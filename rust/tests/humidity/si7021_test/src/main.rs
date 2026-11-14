use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::Si7021Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Si7021Full::new(dev, addr);

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.software_reset(&mut delay).expect("software_reset");
    check_true!(chip.device_type().expect("device_type") == 0x15, "device type");
    check_true!(chip.serial_number().expect("serial_number") > 0, "serial number");
    check_true!(chip.read_user_register().expect("user register") & 0xBF == 0x3A, "user register after reset");

    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t > -40.0 && t < 125.0, "temperature range");
    check_true!(rh >= 0.0 && rh <= 100.0, "humidity range");
    check_true!(chip.read_humidity(&mut delay).expect("rh") >= 0.0, "read_humidity range");
    check_true!(chip.read_temperature(&mut delay).expect("t") > -40.0, "read_temperature range");
    check_true!(chip.read_temperature_previous().expect("prev") > -40.0, "read_temperature_previous range");
    check_true!(chip.read_raw_humidity(&mut delay).is_ok(), "raw humidity ok");
    check_true!(chip.read_raw_temperature(&mut delay).is_ok(), "raw temperature ok");

    for (rb, tb) in [(12u8, 14u8), (8, 12), (10, 13), (11, 11)] {
        chip.set_resolution(rb, tb).expect("set_resolution");
        check_true!(chip.get_resolution().expect("get_resolution") == (rb, tb), "resolution roundtrip");
        check_true!(chip.read(&mut delay).is_ok(), "read at resolution");
    }
    chip.set_resolution(12, 14).expect("set_resolution");
    let ct = chip.conversion_time().expect("conversion_time");
    check_true!(ct > 0.005 && ct < 0.03, "conversion time");

    chip.set_heater(true).expect("heater on");
    check_true!(chip.is_heater_on().expect("is_heater_on"), "heater on");
    chip.set_heater(false).expect("heater off");
    check_true!(!chip.is_heater_on().expect("is_heater_on"), "heater off");
    check_true!(!chip.vdd_low().expect("vdd_low"), "vdd ok");
    check_true!(chip.firmware_revision().is_ok(), "firmware readable");

    let dp = chip.dew_point(&mut delay).expect("dew_point");
    check_true!(dp > -60.0 && dp < 60.0, "dew point plausible");
    chip.software_reset(&mut delay).expect("software_reset");
    check_true!(chip.read(&mut delay).is_ok(), "read after software reset");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
