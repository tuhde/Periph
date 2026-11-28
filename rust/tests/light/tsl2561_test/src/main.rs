use linux_embedded_hal::I2cdev;
use linux_embedded_hal::Delay;
use periph::chips::light::Tsl2561Full;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    #[allow(non_snake_case)]
    let mut DELAY = Delay;
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR").ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x39);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Tsl2561Full::new(i2c, addr);

    sensor.init(&mut DELAY).expect("init");
    let lx = sensor.read_lux().expect("lux");
    check_true!(lx >= 0.0 && lx <= 40000.0, "read_lux_in_range", passed, failed);
    check_true!(sensor.read_id().expect("id") >> 4 == 1, "read_id_partno", passed, failed);
    check_true!(sensor.is_powered().expect("powered"), "is_powered", passed, failed);
    check_true!(sensor.read_raw().is_ok(), "read_raw", passed, failed);

    sensor.set_gain(16).expect("gain");
    sensor.set_integration_time(101).expect("integ");
    check_true!(sensor.get_gain() == 16 && sensor.get_integration_time() == 101, "gain_integration_roundtrip", passed, failed);
    sensor.set_gain(1).expect("gain");
    sensor.set_integration_time(402).expect("integ");
    let lx = sensor.auto_range(&mut DELAY).expect("auto");
    check_true!(lx >= 0.0, "auto_range_lux", passed, failed);

    sensor.set_thresholds(100, 30000).expect("thr");
    check_true!(sensor.get_thresholds().expect("thr") == (100, 30000), "thresholds_roundtrip", passed, failed);
    check_true!(sensor.configure_interrupt(0, 0).is_ok(), "configure_interrupt", passed, failed);
    check_true!(sensor.clear_interrupt().is_ok(), "clear_interrupt", passed, failed);
    check_true!(sensor.poll_interrupt().is_ok(), "poll_interrupt", passed, failed);
    sensor.power_down().expect("down");
    check_true!(!sensor.is_powered().expect("powered"), "power_down", passed, failed);
    sensor.power_up().expect("up");
    check_true!(sensor.is_powered().expect("powered"), "power_up", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
