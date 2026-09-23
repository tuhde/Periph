use linux_embedded_hal::I2cdev;
use periph::chips::temperature::{
    Mcp9808AlertMode, Mcp9808AlertOutput, Mcp9808AlertPolarity, Mcp9808Full, Mcp9808Minimal,
    MCP9808_SOURCE_LOWER,
};
use std::thread::sleep;
use std::time::Duration;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR").ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x18);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut minimal = Mcp9808Minimal::new(dev, addr).expect("init MCP9808");
    let t = minimal.read_temperature().expect("read_temperature");
    check_true!(t >= -40.0 && t <= 125.0, "temperature_plausible", passed, failed);
    let i2c = minimal.release();

    let mut full = Mcp9808Full::new(i2c, addr).expect("init MCP9808 Full");
    full.set_resolution(0.5).expect("set_resolution");
    check_true!(full.get_resolution().expect("get_resolution") == 0.5, "resolution_0_5", passed, failed);
    full.set_resolution(0.0625).expect("set_resolution");
    check_true!(full.get_resolution().expect("get_resolution") == 0.0625, "resolution_0_0625", passed, failed);

    full.set_upper_limit(80.0).expect("set_upper_limit");
    check_true!(full.get_upper_limit().expect("get_upper_limit") == 80.0, "upper_limit_roundtrip", passed, failed);
    full.set_lower_limit(-10.25).expect("set_lower_limit");
    check_true!(full.get_lower_limit().expect("get_lower_limit") == -10.25, "lower_limit_roundtrip", passed, failed);
    full.set_critical_limit(100.0).expect("set_critical_limit");
    check_true!(full.get_critical_limit().expect("get_critical_limit") == 100.0, "critical_limit_roundtrip", passed, failed);

    full.set_hysteresis(1.5).expect("set_hysteresis");
    check_true!(full.get_hysteresis().expect("get_hysteresis") == 1.5, "hysteresis_roundtrip", passed, failed);
    full.set_hysteresis(0.0).expect("set_hysteresis");

    full.shutdown().expect("shutdown");
    check_true!(full.is_shutdown().expect("is_shutdown"), "shutdown", passed, failed);
    full.wake().expect("wake");
    check_true!(!full.is_shutdown().expect("is_shutdown"), "wake", passed, failed);

    // Lower limit above ambient forces TA < TLOWER; the status bit is live
    // regardless of whether the Alert output is enabled.
    full.set_lower_limit(t + 20.0).expect("set_lower_limit");
    sleep(Duration::from_millis(300));
    check_true!(full.poll_interrupt().expect("poll_interrupt") & MCP9808_SOURCE_LOWER != 0, "poll_interrupt_lower", passed, failed);
    full.set_lower_limit(-10.25).expect("set_lower_limit");
    sleep(Duration::from_millis(300));
    check_true!(full.poll_interrupt().expect("poll_interrupt") & MCP9808_SOURCE_LOWER == 0, "poll_interrupt_clear", passed, failed);

    full.configure_alert(Mcp9808AlertMode::All, Mcp9808AlertOutput::Comparator, Mcp9808AlertPolarity::ActiveLow)
        .expect("configure_alert");
    full.enable_alert().expect("enable_alert");
    check_true!(!full.is_alert_asserted().expect("is_alert_asserted"), "alert_not_asserted_in_window", passed, failed);
    full.disable_alert().expect("disable_alert");
    full.clear_interrupt().expect("clear_interrupt");

    // The lock bits are one-way until power-on reset and are never set here.
    check_true!(!full.is_critical_limit_locked().expect("locked"), "not_critical_locked", passed, failed);
    check_true!(!full.is_window_limits_locked().expect("locked"), "not_window_locked", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
