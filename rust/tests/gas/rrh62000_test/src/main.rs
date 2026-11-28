//! RRH62000 hardware-in-loop test for Linux.
use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::gas::Rrh62000Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x69);

    let mut passed = 0i32;
    let mut failed = 0i32;
    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let delay = Delay;
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }
    let mut chip = Rrh62000Full::new(dev, delay, addr);

    chip.init().expect("init");
    check_true!(chip.firmware_version().is_ok(), "firmware_version");
    check_true!(chip.algorithm_version().is_ok(), "algorithm_version");
    check_true!(chip.unique_id().is_ok(), "unique_id");
    let r = chip.read();
    check_true!(r.is_ok(), "read");
    if let Ok(m) = r {
        check_true!(m.pm2_5_ug_m3 >= 0.0 && m.pm2_5_ug_m3 <= 1000.0, "read_pm");
        check_true!(m.temperature_c >= -20.0 && m.temperature_c <= 60.0, "read_temperature");
        check_true!(m.humidity_rh >= 0.0 && m.humidity_rh <= 100.0, "read_humidity");
        check_true!(m.iaq >= 0.0 && m.iaq <= 5.0, "read_iaq");
    }
    check_true!(chip.read_all().map(|d| d.particles.nc_0_3 >= d.particles.nc_0_5).unwrap_or(false), "read_all");
    check_true!(chip.read_status().map(|s| !s.fan).unwrap_or(false), "read_status");
    check_true!(chip.read_particles().is_ok(), "read_particles");
    check_true!(chip.data_ready().is_ok(), "data_ready");
    let avg = chip.get_moving_average().unwrap_or(10);
    check_true!(chip.set_moving_average(20).is_ok() && chip.get_moving_average().ok() == Some(20), "moving_average");
    chip.set_moving_average(avg).ok();
    let fan = chip.get_fan_speed().unwrap_or(86);
    check_true!(chip.set_fan_speed(70).is_ok() && chip.get_fan_speed().ok() == Some(70), "fan_speed");
    chip.set_fan_speed(fan).ok();
    let interval = chip.get_cleaning_interval().unwrap_or(604_800);
    check_true!(chip.set_cleaning_interval(86_400).is_ok() && chip.get_cleaning_interval().ok() == Some(86_400), "cleaning_interval");
    chip.set_cleaning_interval(interval).ok();
    let t = chip.get_cleaning_time().unwrap_or(10);
    check_true!(chip.set_cleaning_time(12).is_ok() && chip.get_cleaning_time().ok() == Some(12), "cleaning_time");
    chip.set_cleaning_time(t).ok();
    check_true!(chip.read_mox_resistance().map(|r| r > 0).unwrap_or(false), "mox_resistance");
    check_true!(chip.cleaning_done().is_ok(), "cleaning_done");
    check_true!(chip.sleep().is_ok(), "sleep");
    sleep_ms!(3000);
    check_true!(chip.wake().is_ok() && chip.read().is_ok(), "wake_read");
    check_true!(chip.soft_reset().is_ok() && chip.read().is_ok(), "soft_reset_read");
    sleep_ms!(10);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
