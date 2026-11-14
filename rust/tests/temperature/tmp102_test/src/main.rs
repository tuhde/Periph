use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::temperature::Tmp102Full;
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
    const ADDR_DEFAULT: u8 = 0x48;
    let addr: u8 = std::env::var("I2C_ADDR").ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(ADDR_DEFAULT);
    let mut delay = Delay;

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut full = Tmp102Full::new(i2c, addr);
    sleep(Duration::from_millis(50));
    let t = full.read_temperature().expect("read_temperature");
    check_true!(t >= -40.0 && t <= 125.0, "temperature_plausible", passed, failed);
    let raw_t = full.read_raw().expect("read_raw") as f32 * 0.0625;
    check_true!((raw_t - t).abs() < 1.0, "raw_matches_temperature", passed, failed);
    check_true!(full.read_config().expect("read_config") & 0x6000 == 0x6000, "default_config", passed, failed);

    full.set_conversion_rate(1.0).ok().expect("rate");
    check_true!((full.read_config().expect("read_config") >> 6) & 3 == 1, "rate_roundtrip", passed, failed);
    full.set_conversion_rate(4.0).ok().expect("rate");

    full.set_t_high(40.0).expect("set_t_high");
    check_true!(full.get_t_high().expect("get_t_high") == 40.0, "t_high_roundtrip", passed, failed);
    full.set_t_low(-10.25).expect("set_t_low");
    check_true!(full.get_t_low().expect("get_t_low") == -10.25, "t_low_roundtrip", passed, failed);
    full.set_t_high(80.0).expect("set_t_high");
    full.set_t_low(75.0).expect("set_t_low");

    full.set_fault_queue(4).ok().expect("fault queue");
    check_true!((full.read_config().expect("read_config") >> 11) & 3 == 2, "fault_queue", passed, failed);
    full.set_fault_queue(1).ok().expect("fault queue");
    full.set_polarity(true).expect("polarity");
    check_true!(full.read_config().expect("read_config") & 0x0400 != 0, "polarity", passed, failed);
    full.set_polarity(false).expect("polarity");
    full.set_thermostat_mode(true).expect("tm");
    check_true!(full.read_config().expect("read_config") & 0x0200 != 0, "thermostat_mode", passed, failed);
    full.set_thermostat_mode(false).expect("tm");

    full.set_extended_mode(true).expect("em");
    sleep(Duration::from_millis(300));
    let ext = full.read_temperature().expect("read_temperature");
    check_true!((ext - t).abs() < 3.0, "extended_mode_reading", passed, failed);
    full.set_extended_mode(false).expect("em");

    full.shutdown().expect("shutdown");
    check_true!(full.read_config().expect("read_config") & 0x0100 != 0, "shutdown", passed, failed);
    let t1 = full.one_shot(&mut delay).ok();
    check_true!(matches!(t1, Some(v) if v >= -40.0 && v <= 125.0), "one_shot_plausible", passed, failed);
    check_true!(full.read_config().expect("read_config") & 0x0100 != 0, "one_shot_stays_shutdown", passed, failed);
    full.wake().expect("wake");
    check_true!(full.read_config().expect("read_config") & 0x0100 == 0, "wake", passed, failed);

    // T_HIGH below ambient forces an alert after the fault queue elapses.
    full.set_thermostat_mode(false).expect("tm");
    full.set_t_low(t - 21.0).expect("set_t_low");
    full.set_t_high(t - 20.0).expect("set_t_high");
    sleep(Duration::from_millis(500));
    check_true!(full.alert_state().expect("alert_state"), "alert_state_active", passed, failed);
    check_true!(full.poll_interrupt().expect("poll_interrupt"), "poll_interrupt_active", passed, failed);
    full.set_t_high(80.0).expect("set_t_high");
    full.set_t_low(75.0).expect("set_t_low");
    sleep(Duration::from_millis(500));
    check_true!(!full.alert_state().expect("alert_state"), "alert_state_clear", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
}
