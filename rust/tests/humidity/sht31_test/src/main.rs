use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::{Repeatability, Sht31Full};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x44);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Sht31Full::new(dev, addr, &mut delay).expect("init");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t > -40.0 && t < 125.0, "temperature range");
    check_true!(rh >= 0.0 && rh <= 100.0, "humidity range");
    check_true!(chip.read_temperature(&mut delay).expect("t") > -40.0, "read_temperature range");
    check_true!(chip.read_humidity(&mut delay).expect("rh") >= 0.0, "read_humidity range");
    check_true!(chip.read_raw(&mut delay).is_ok(), "read_raw ok");

    for level in [Repeatability::Low, Repeatability::Medium, Repeatability::High] {
        chip.set_repeatability(level);
        let (t2, rh2) = chip.read(&mut delay).expect("read level");
        check_true!(t2 > -40.0 && t2 < 125.0 && rh2 >= 0.0 && rh2 <= 100.0, "repeatability read");
    }

    check_true!(chip.read_status().is_ok(), "status readable");
    chip.clear_status().expect("clear_status");
    check_true!(chip.read_status().expect("status") & 0x0010 == 0, "status bit 4 cleared");

    chip.start_periodic(2.0, Repeatability::High, false).expect("start_periodic");
    std::thread::sleep(std::time::Duration::from_millis(1200));
    let res = chip.fetch().expect("fetch");
    check_true!(res.is_some(), "periodic fetch");
    chip.stop_periodic(&mut delay).expect("stop_periodic");

    chip.set_heater(true).expect("heater on");
    check_true!(chip.read_status().expect("status") & 0x2000 != 0, "heater bit set");
    chip.set_heater(false).expect("heater off");
    check_true!(chip.read_status().expect("status") & 0x2000 == 0, "heater bit cleared");

    let dp = chip.dew_point(&mut delay).expect("dew_point");
    check_true!(dp > -60.0 && dp < 60.0, "dew point plausible");
    chip.soft_reset(&mut delay).expect("soft_reset");
    check_true!(chip.read(&mut delay).is_ok(), "read after soft reset");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
