use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::color::AS7262Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x49);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = AS7262Full::new(dev, addr);

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.init(&mut delay).expect("init");

    check_true!(chip.device_type(&mut delay).expect("type") == 0x40, "device type");
    check_true!(chip.hardware_version(&mut delay).expect("hw") == (0x40, 0x3E), "hardware version");
    let t = chip.temperature(&mut delay).expect("temperature");
    check_true!(t >= -40.0 && t <= 125.0, "temperature range");

    let d = chip.read(&mut delay).expect("read");
    check_true!(d.to_array().iter().all(|v| *v >= 0.0), "calibrated channels");
    let r = chip.read_raw(&mut delay).expect("read_raw");
    check_true!(r.to_array().len() == 6, "raw channels");
    let raw2 = chip.read_channel_raw(2, &mut delay).expect("channel");
    check_true!(raw2 == raw2, "read_channel_raw");

    chip.set_gain(64.0, &mut delay).expect("set_gain");
    check_true!(chip.get_gain(&mut delay).expect("get_gain") == 64.0, "gain");
    chip.set_gain(16.0, &mut delay).expect("set_gain");
    chip.set_integration_time(50.0, &mut delay).expect("set_integration_time");
    check_true!((chip.get_integration_time(&mut delay).expect("get") - 50.4).abs() < 0.01, "integration time");
    chip.set_mode(2, &mut delay).expect("set_mode");
    check_true!(chip.get_mode(&mut delay).expect("get_mode") == 2, "mode");
    chip.set_mode(3, &mut delay).expect("set_mode");

    chip.measure(&mut delay).expect("measure");
    std::thread::sleep(std::time::Duration::from_millis(600));
    check_true!(chip.data_ready(&mut delay).expect("data_ready"), "data ready");
    let _ = chip.read_calibrated(&mut delay).expect("read_calibrated");

    chip.set_indicator_led(true, 4.0, &mut delay).expect("led");
    chip.set_indicator_led(false, 1.0, &mut delay).expect("led");
    chip.soft_reset(&mut delay).expect("soft_reset");
    check_true!(chip.get_integration_time(&mut delay).expect("get") > 700.0, "reset int_t");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
