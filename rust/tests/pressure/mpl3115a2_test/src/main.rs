use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::pressure::mpl3115a2;
use periph::chips::pressure::MPL3115A2Full;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x60);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }
    let mut chip = MPL3115A2Full::new(dev, addr);

    chip.init(&mut delay).expect("init");
    check_true!(chip.who_am_i().expect("who_am_i") == 0xC4, "who_am_i", passed, failed);
    let (p, t) = chip.read(&mut delay).expect("read");
    check_true!(p > 20000.0 && p < 110000.0, "read_pressure_range", passed, failed);
    check_true!(t > -40.0 && t < 85.0, "read_temperature_range", passed, failed);
    let p1 = chip.read_pressure(&mut delay).expect("p");
    check_true!(p1 > 20000.0 && p1 < 110000.0, "read_pressure_single", passed, failed);
    let t1 = chip.read_temperature(&mut delay).expect("t");
    check_true!(t1 > -40.0 && t1 < 85.0, "read_temperature_single", passed, failed);
    for ratio in [1u8, 8, 128] {
        chip.set_oversample(ratio).expect("oversample");
        check_true!(chip.get_oversample() == ratio, "oversample_roundtrip", passed, failed);
        let r = chip.read_pressure(&mut delay).expect("read");
        check_true!(r > 20000.0 && r < 110000.0, "read_at_osr", passed, failed);
    }
    chip.set_oversample(16).expect("oversample");
    chip.set_sea_level_pressure(101325.0).expect("sea level");
    let alt = chip.read_altitude(&mut delay).expect("altitude");
    check_true!(alt > -500.0 && alt < 9000.0, "read_altitude_range", passed, failed);
    chip.set_mode(false).expect("mode");
    chip.start_continuous(0).expect("start");
    check_true!(chip.is_active().expect("active"), "is_active", passed, failed);
    let (v, _) = chip.read_continuous(&mut delay).expect("continuous");
    check_true!(v > 20000.0 && v < 110000.0, "read_continuous", passed, failed);
    chip.stop_continuous().expect("stop");
    check_true!(!chip.is_active().expect("active"), "is_standby", passed, failed);
    chip.fifo_setup(mpl3115a2::FIFO_CIRCULAR, 4).expect("fifo");
    chip.start_continuous(0).expect("start");
    sleep_ms!(5000);
    let (_, _, count) = chip.fifo_status().expect("fifo_status");
    check_true!(count >= 2, "fifo_collects", passed, failed);
    let mut samples = [(0.0f32, 0.0f32); 32];
    check_true!(chip.fifo_read(&mut samples).expect("fifo_read") >= 2, "fifo_read_count", passed, failed);
    chip.stop_continuous().expect("stop");
    chip.fifo_setup(mpl3115a2::FIFO_OFF, 0).expect("fifo off");
    chip.enable_interrupt(mpl3115a2::SOURCE_DRDY, 1).expect("enable");
    chip.disable_interrupt(mpl3115a2::SOURCE_DRDY).expect("disable");
    chip.reset(&mut delay).expect("reset");
    let r = chip.read_pressure(&mut delay).expect("read");
    check_true!(r > 20000.0 && r < 110000.0, "read_after_reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
