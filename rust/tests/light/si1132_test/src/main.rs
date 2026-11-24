use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::Si1132Full;

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
        .unwrap_or(0x60);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }
    let mut chip = Si1132Full::new(dev, addr);


    chip.init(&mut delay).expect("init");
    let r = chip.read(&mut delay).expect("read");
    check_true!(r.uv_index >= 0.0 && r.uv_index < 20.0, "read_uv_index_range", passed, failed);
    check_true!(chip.read_uv_index(&mut delay).expect("uv") < 20.0, "read_uv_index_single", passed, failed);
    check_true!(chip.read_visible(&mut delay).is_ok(), "read_visible_single", passed, failed);
    check_true!(chip.read_infrared(&mut delay).is_ok(), "read_infrared_single", passed, failed);
    check_true!(chip.read_raw().is_ok(), "read_raw", passed, failed);
    check_true!(chip.read_temperature_raw(&mut delay).is_ok(), "temperature_raw", passed, failed);
    check_true!(chip.read_vdd_raw(&mut delay).is_ok(), "vdd_raw", passed, failed);
    chip.set_gain(&mut delay, 2, 2).expect("gain");
    check_true!(chip.read_visible(&mut delay).is_ok(), "gain_read", passed, failed);
    chip.set_gain(&mut delay, 0, 0).expect("gain");
    chip.set_high_range(&mut delay, true, true).expect("range");
    check_true!(chip.read_visible(&mut delay).is_ok(), "high_range_read", passed, failed);
    chip.set_high_range(&mut delay, false, false).expect("range");
    chip.set_channels(&mut delay, true, false, false, false).expect("channels");
    check_true!(chip.read(&mut delay).is_ok(), "uv_only_read", passed, failed);
    chip.set_channels(&mut delay, true, true, true, false).expect("channels");
    check_true!(chip.set_channels(&mut delay, false, false, false, false).is_err(), "channels_invalid", passed, failed);
    check_true!(chip.read_calibration(&mut delay).is_ok(), "read_calibration", passed, failed);
    chip.force(&mut delay).expect("force");
    sleep_ms!(50);
    check_true!(chip.measurement_ready().expect("ready"), "measurement_ready", passed, failed);
    check_true!(chip.read_response().expect("response").1 == 0, "response_no_error", passed, failed);
    let _ = chip.poll_interrupt();
    chip.start_autonomous(&mut delay, 0.1).expect("autonomous");
    sleep_ms!(500);
    check_true!(chip.measurement_ready().expect("ready"), "autonomous_ready", passed, failed);
    chip.pause(&mut delay).expect("pause");
    chip.enable_interrupt().expect("enable interrupt");
    chip.disable_interrupt().expect("disable interrupt");
    chip.reset(&mut delay).expect("reset");
    check_true!(chip.read(&mut delay).is_ok(), "read_after_reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
