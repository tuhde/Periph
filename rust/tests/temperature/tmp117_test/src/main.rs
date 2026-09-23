use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::temperature::{
    Tmp117AlertMode, Tmp117AlertPinFunction, Tmp117AlertPolarity, Tmp117Full, Tmp117Minimal, Tmp117Mode,
    TMP117_SOURCE_HIGH,
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
        .unwrap_or(0x48);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut minimal = Tmp117Minimal::new(i2c, addr).expect("init TMP117");
    check_true!(minimal.read_temperature().is_ok(), "construct_minimal", passed, failed);
    let i2c = minimal.release();

    let mut full = Tmp117Full::new(i2c, addr).expect("init TMP117 Full");
    full.configure(Tmp117Mode::Continuous, 8, 0.125).expect("configure");
    sleep(Duration::from_millis(300));
    let t = full.read_temperature().expect("read_temperature");
    check_true!(t >= -40.0 && t <= 125.0, "temperature_plausible", passed, failed);

    full.configure(Tmp117Mode::Continuous, 32, 4.0).expect("configure");
    let cfg = full.get_config().expect("get_config");
    check_true!(cfg.mode == Tmp117Mode::Continuous && cfg.averaging == 32 && cfg.cycle_seconds == 4.0,
                "config_roundtrip", passed, failed);
    full.configure(Tmp117Mode::Shutdown, 0, 0.0155).expect("configure");
    check_true!(full.is_shutdown().expect("is_shutdown"), "shutdown", passed, failed);
    full.trigger_one_shot().expect("trigger_one_shot");
    sleep(Duration::from_millis(50));
    check_true!(full.is_data_ready().expect("is_data_ready"), "one_shot_data_ready", passed, failed);
    check_true!(full.is_shutdown().expect("is_shutdown"), "one_shot_returns_to_shutdown", passed, failed);
    full.configure(Tmp117Mode::Continuous, 8, 1.0).expect("configure");
    check_true!(!full.is_shutdown().expect("is_shutdown"), "continuous", passed, failed);

    full.set_high_limit(80.0).expect("set_high_limit");
    check_true!(full.get_high_limit().expect("get_high_limit") == 80.0, "high_limit_roundtrip", passed, failed);
    full.set_low_limit(-10.25).expect("set_low_limit");
    check_true!(full.get_low_limit().expect("get_low_limit") == -10.25, "low_limit_roundtrip", passed, failed);
    full.set_temperature_offset(0.5).expect("set_temperature_offset");
    check_true!(full.get_temperature_offset().expect("get_temperature_offset") == 0.5, "offset_roundtrip", passed, failed);
    full.set_temperature_offset(0.0).expect("set_temperature_offset");

    // The EEPROM is never unlocked here, so no power-on default changes.
    check_true!(!full.is_eeprom_busy().expect("is_eeprom_busy"), "eeprom_not_busy", passed, failed);
    full.write_eeprom_scratch(2, 0xA55A).expect("write_eeprom_scratch");
    check_true!(full.read_eeprom_scratch(2).expect("read_eeprom_scratch") == 0xA55A, "eeprom2_volatile_roundtrip", passed, failed);

    // High limit below ambient forces HIGH_Alert on the next conversion; in
    // Alert mode the flag latches until CONFIGURATION is read.
    full.configure_alert(Tmp117AlertMode::Alert, Tmp117AlertPolarity::ActiveLow, Tmp117AlertPinFunction::Alert)
        .expect("configure_alert");
    full.configure(Tmp117Mode::Continuous, 0, 0.0155).expect("configure");
    full.set_high_limit(t - 20.0).expect("set_high_limit");
    sleep(Duration::from_millis(100));
    check_true!(full.poll_interrupt().expect("poll_interrupt") & TMP117_SOURCE_HIGH != 0, "poll_interrupt_high", passed, failed);
    full.set_high_limit(80.0).expect("set_high_limit");
    sleep(Duration::from_millis(100));
    full.poll_interrupt().expect("poll_interrupt");
    check_true!(full.poll_interrupt().expect("poll_interrupt") & TMP117_SOURCE_HIGH == 0, "poll_interrupt_clear", passed, failed);

    // Soft reset reloads CONFIGURATION, the limits and the offset from EEPROM.
    full.reset(&mut Delay).expect("reset");
    check_true!(full.get_config().expect("get_config").mode == Tmp117Mode::Continuous, "reset_restores_config", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
