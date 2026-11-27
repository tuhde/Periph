//! PN532 hardware-in-loop test for Linux.
use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::rfid::pn532;
use periph::chips::rfid::Pn532Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x24);

    let mut passed = 0i32;
    let mut failed = 0i32;
    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }
    let mut chip = Pn532Full::new(dev, addr);

    chip.init(&mut delay).expect("init");
    let fw = chip.firmware_version(&mut delay).expect("firmware");
    check_true!(fw.ic == 0x32, "firmware_ic");
    check_true!(fw.iso14443a, "firmware_support_a");
    check_true!(chip.general_status(&mut delay).expect("status").targets <= 2, "general_status_targets");
    check_true!(chip.self_test(&mut delay, 0x00).unwrap_or(false), "self_test_echo");
    check_true!(chip.self_test(&mut delay, 0x01).unwrap_or(false), "self_test_rom");
    check_true!(chip.send_command(&mut delay, 0x02, &[], 1000).map(|d| d[0] == 0x32).unwrap_or(false), "send_command");
    check_true!(chip.start_command(&mut delay, 0x02, &[]).is_ok(), "start_command");
    check_true!(chip.wait_ready(&mut delay, 1000).unwrap_or(false), "wait_ready");
    check_true!(chip.poll_interrupt().unwrap_or(false), "poll_interrupt");
    check_true!(chip.read_response(&mut delay, 0x02, 1000).map(|d| d[0] == 0x32).unwrap_or(false), "read_response");
    let reg = chip.read_register(&mut delay, 0x6302).expect("read register");
    chip.write_register(&mut delay, 0x6302, reg).expect("write register");
    check_true!(chip.read_register(&mut delay, 0x6302).expect("read register") == reg, "register_roundtrip");
    check_true!(chip.set_rf_field(&mut delay, false, false).is_ok() && chip.set_rf_field(&mut delay, true, false).is_ok(), "rf_field_toggle");
    check_true!(chip.set_max_retries(&mut delay, 2, 0xFF, 1).is_ok(), "set_max_retries");
    let uid = chip.read_uid(&mut delay, 1000);
    check_true!(matches!(&uid, Ok(None)) || matches!(&uid, Ok(Some(u)) if [4, 7, 10].contains(&u.len())), "read_uid_returns");
    let targets = chip.list_passive_targets(&mut delay, pn532::BAUD_106_A, 1, &[], 1000);
    check_true!(targets.is_ok(), "list_passive_targets");
    if let Ok(t) = targets {
        if let Some(t) = t.first() {
            let _ = chip.release(&mut delay, t.tg);
        }
    }
    check_true!(chip.power_down(&mut delay, 0x20).is_ok(), "power_down");
    sleep_ms!(10);
    check_true!(chip.firmware_version(&mut delay).map(|f| f.ic == 0x32).unwrap_or(false), "wake_after_power_down");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
