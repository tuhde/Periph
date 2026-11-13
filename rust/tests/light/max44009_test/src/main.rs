use linux_embedded_hal::I2cdev;
use linux_embedded_hal::Delay;
use periph::chips::light::MAX44009Full;

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
        .unwrap_or(0x4A);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = MAX44009Full::new(dev, addr);

    sensor.init(&mut DELAY).expect("init");
    let lx = sensor.lux().expect("lux");
    check_true!(lx >= 0.0 && lx <= 188006.4, "lux_in_range", passed, failed);
    let (e, m, over) = sensor.read_raw().expect("raw");
    check_true!(e <= 15 && over == (e == 15), "read_raw_ranges", passed, failed);

    sensor.set_continuous(true).expect("cont");
    check_true!(sensor.get_config().expect("cfg").continuous, "continuous_on", passed, failed);
    sensor.set_continuous(false).expect("cont");
    check_true!(!sensor.get_config().expect("cfg").continuous, "continuous_off", passed, failed);
    sensor.set_manual(100.0, false).expect("manual");
    let cfg = sensor.get_config().expect("cfg");
    check_true!(cfg.manual && cfg.integration_ms == 100.0 && !cfg.divide_by_8, "manual_100ms", passed, failed);
    sensor.set_manual(25.0, true).expect("manual");
    let cfg = sensor.get_config().expect("cfg");
    check_true!(cfg.integration_ms == 25.0 && cfg.divide_by_8, "manual_25ms_divide", passed, failed);
    sensor.set_automatic().expect("auto");
    check_true!(!sensor.get_config().expect("cfg").manual, "automatic", passed, failed);

    sensor.set_thresholds(50.0, 500.0).expect("thr");
    let (lo, hi) = sensor.get_thresholds().expect("thr");
    check_true!((lo - 50.0).abs() / 50.0 < 0.2 && (hi - 500.0).abs() / 500.0 < 0.2, "thresholds_roundtrip", passed, failed);
    sensor.set_threshold_delay(2.0).expect("delay");
    check_true!((sensor.get_threshold_delay().expect("delay") - 2.0).abs() < 0.05, "threshold_delay", passed, failed);
    sensor.enable_interrupt().expect("en");
    sensor.disable_interrupt().expect("dis");
    check_true!(sensor.poll_interrupt().expect("status") == 0, "poll_interrupt_cleared", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
