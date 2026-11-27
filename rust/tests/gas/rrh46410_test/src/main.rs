//! RRH46410 hardware-in-loop test for Linux.
use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::gas::rrh46410;
use periph::chips::gas::Rrh46410Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

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
    let mut chip = Rrh46410Full::new(dev, addr);

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

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
