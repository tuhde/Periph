#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::temperature::{
    Mcp9808AlertMode, Mcp9808AlertOutput, Mcp9808AlertPolarity, Mcp9808Full, Mcp9808Minimal,
    MCP9808_SOURCE_LOWER,
};

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const ADDR: u8 = 0x18;

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());
    let delay = Delay::new();

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .expect("i2c config")
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut minimal = Mcp9808Minimal::new(i2c, ADDR).expect("init MCP9808");
    let t = minimal.read_temperature().expect("read_temperature");
    check_true!(t >= -40.0 && t <= 125.0, "temperature_plausible", passed, failed);
    let i2c = minimal.release();

    let mut full = Mcp9808Full::new(i2c, ADDR).expect("init MCP9808 Full");
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
    delay.delay_millis(300);
    check_true!(full.poll_interrupt().expect("poll_interrupt") & MCP9808_SOURCE_LOWER != 0, "poll_interrupt_lower", passed, failed);
    full.set_lower_limit(-10.25).expect("set_lower_limit");
    delay.delay_millis(300);
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
    loop {
        delay.delay_millis(1000);
    }
}
