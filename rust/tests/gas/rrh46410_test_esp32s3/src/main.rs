//! RRH46410 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; address 0x38.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::gas::rrh46410;
use periph::chips::gas::Rrh46410Full;

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }
    let mut chip = Rrh46410Full::new(i2c, 0x38);

    esp_println::println!("RRH46410 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.init(&mut delay).expect("init");
    check_true!(chip.product_id().map(|(p, _)| p == 0x2310).unwrap_or(false), "product_id");
    check_true!(chip.tracking_number().is_ok(), "tracking_number");
    check_true!(chip.get_mode().map(|m| m == rrh46410::MODE_IAQ_2ND_GEN).unwrap_or(false), "mode_iaq");
    check_true!(chip.set_humidity(50.0).is_ok() && chip.status().raw == 0, "set_humidity");
    let r = chip.read(&mut delay);
    check_true!(r.is_ok(), "read");
    if let Ok(m) = r {
        check_true!(m.iaq >= 0.0 && m.iaq <= 25.5, "read_iaq_range");
        check_true!(!m.warmed_up, "read_not_warmed_up");
    }
    check_true!(chip.wait_for_sample(&mut delay, 5000).unwrap_or(false), "wait_for_sample");
    check_true!(matches!(chip.read_results(), Ok(Some(_))), "read_results");
    check_true!(!chip.poll_interrupt().unwrap_or(true), "poll_interrupt_false");
    check_true!(chip.set_mode(rrh46410::MODE_PBAQ).is_ok() && chip.get_mode().map(|m| m == rrh46410::MODE_PBAQ).unwrap_or(false), "mode_pbaq");
    check_true!(chip.read(&mut delay).map(|m| m.pbaq).unwrap_or(false), "read_pbaq");
    check_true!(chip.config_gpio(0x03).is_ok() && chip.set_gpio(0x01).is_ok() && chip.get_gpio().map(|g| g & 1 == 1).unwrap_or(false), "set_gpio");
    check_true!(chip.clear_gpio(0x01).is_ok() && chip.get_gpio().map(|g| g & 1 == 0).unwrap_or(false), "clear_gpio");
    let mut mode = [0u8; 1];
    check_true!(chip.send_command(0x10, &[], &mut mode).is_ok() && mode[0] == rrh46410::MODE_PBAQ, "send_command");
    check_true!(chip.suspend().is_ok() && chip.get_mode().map(|m| m == rrh46410::MODE_SUSPEND).unwrap_or(false), "suspend");
    check_true!(chip.reset(&mut delay).is_ok() && chip.get_mode().map(|m| m == rrh46410::MODE_SUSPEND).unwrap_or(false), "reset");
    sleep_ms!(10);

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        sleep_ms!(1000);
    }
}
