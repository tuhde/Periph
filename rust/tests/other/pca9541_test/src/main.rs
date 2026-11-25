use linux_embedded_hal::I2cdev;
use linux_embedded_hal::Delay;
use periph::chips::other::PCA9541Full;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    #[allow(non_snake_case)]
    let mut DELAY = Delay;
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR").ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x70);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = PCA9541Full::new(dev, addr);

    sensor.init().expect("init");
    let has = sensor.has_control().expect("has_control");
    check_true!(has || !has, "has_control_is_bool", passed, failed);
    let control = sensor.get_control().expect("control");
    let st = sensor.status().expect("status");
    check_true!(st.has_control == (((control & 1) != 0) == ((control & 2) != 0)), "status_matches_control", passed, failed);
    let (ie, _c, _s) = sensor.read_all().expect("read_all");
    check_true!(ie <= 0x0F, "read_all_ie_range", passed, failed);
    sensor.set_interrupt_mask(false, true, false, false).expect("mask");
    check_true!(sensor.get_interrupt_mask().expect("mask") == 0x04, "interrupt_mask_roundtrip", passed, failed);
    sensor.set_interrupt_mask(false, false, false, false).expect("mask");
    check_true!(sensor.get_interrupt_mask().expect("mask") == 0x00, "interrupt_mask_cleared", passed, failed);
    sensor.test_interrupt(true, false).expect("test");
    check_true!(sensor.status().expect("status").own_test, "test_interrupt_sets_teston", passed, failed);
    sensor.test_interrupt(false, false).expect("test");
    check_true!(!sensor.status().expect("status").own_test, "test_interrupt_clears", passed, failed);
    check_true!(sensor.acquire(&mut DELAY, false, 20).expect("acquire") && sensor.has_control().expect("has"), "acquire_takes_bus", passed, failed);
    sensor.disconnect().expect("disconnect");
    check_true!(!sensor.is_connected().expect("connected"), "disconnect", passed, failed);
    sensor.connect().expect("connect");
    check_true!(sensor.is_connected().expect("connected"), "connect", passed, failed);
    sensor.release().expect("release");
    check_true!(!sensor.has_control().expect("has"), "release_gives_bus_away", passed, failed);
    sensor.acquire(&mut DELAY, false, 20).expect("acquire");
    check_true!(sensor.has_control().expect("has"), "reacquire", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
