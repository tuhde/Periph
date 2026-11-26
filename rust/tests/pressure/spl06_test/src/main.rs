use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::pressure::spl06;
use periph::chips::pressure::{Spl06FifoEntry, Spl06Full, Spl06Kind};

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
        .unwrap_or(0x77);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }
    let mut chip = Spl06Full::new(dev, addr);

    chip.init(&mut delay).expect("init");
    let c = chip.coefficients();
    check_true!(c.c0 != 0 || c.c1 != 0 || c.c00 != 0 || c.c10 != 0, "coefficients_decoded", passed, failed);
    let m = chip.read(&mut delay).expect("read");
    check_true!(m.pressure_pa > 30000.0 && m.pressure_pa < 110000.0, "read_pressure_range", passed, failed);
    check_true!(m.temperature_c > -40.0 && m.temperature_c < 85.0, "read_temperature_range", passed, failed);
    let p = chip.read_pressure(&mut delay).expect("pressure");
    check_true!(p > 30000.0 && p < 110000.0, "read_pressure_single", passed, failed);
    let t = chip.read_temperature(&mut delay).expect("temperature");
    check_true!(t > -40.0 && t < 85.0, "read_temperature_single", passed, failed);
    let (praw, traw) = chip.read_raw(&mut delay).expect("raw");
    let cm = chip.compensate(praw, traw, 16, 1).expect("compensate");
    check_true!(cm.pressure_pa > 30000.0 && cm.pressure_pa < 110000.0, "compensate_raw", passed, failed);
    let alt = chip.altitude(&mut delay, 101325.0).expect("altitude");
    check_true!(alt > -500.0 && alt < 9000.0, "altitude_range", passed, failed);
    for osr in [1u8, 8, 128] {
        chip.set_pressure_config(osr, 1).expect("pressure config");
        let r = chip.read_pressure(&mut delay).expect("read");
        check_true!(r > 30000.0 && r < 110000.0, "read_at_osr", passed, failed);
    }
    chip.set_pressure_config(16, 8).expect("pressure config");
    chip.set_temperature_config(1, 8).expect("temperature config");
    chip.start_continuous(true, true).expect("start");
    let rc = chip.read_continuous(&mut delay).expect("continuous");
    check_true!(rc.pressure_pa > 30000.0 && rc.pressure_pa < 110000.0, "read_continuous", passed, failed);
    sleep_ms!(300);
    check_true!(chip.data_ready().expect("ready").0, "data_ready", passed, failed);
    chip.stop().expect("stop");
    chip.flush_fifo().expect("flush");
    chip.enable_fifo().expect("fifo on");
    chip.start_continuous(true, true).expect("start");
    sleep_ms!(1000);
    chip.stop().expect("stop");
    check_true!(!chip.fifo_status().expect("status").0, "fifo_not_empty", passed, failed);
    let mut entries = [Spl06FifoEntry::default(); 32];
    let n = chip.read_fifo(&mut entries).expect("fifo");
    check_true!(n >= 4, "fifo_read", passed, failed);
    check_true!(entries[..n].iter().filter(|e| e.kind == Spl06Kind::Pressure)
        .all(|e| e.value > 30000.0 && e.value < 110000.0), "fifo_values", passed, failed);
    check_true!(chip.fifo_status().expect("status").0, "fifo_empty_after_read", passed, failed);
    chip.disable_fifo().expect("fifo off");
    chip.enable_interrupt(spl06::SOURCE_PRESSURE).expect("enable");
    chip.poll_interrupt().expect("poll");
    chip.read(&mut delay).expect("read");
    check_true!(chip.poll_interrupt().expect("poll") & spl06::SOURCE_PRESSURE != 0, "poll_interrupt_pressure", passed, failed);
    chip.disable_interrupt(spl06::SOURCE_PRESSURE).expect("disable");
    chip.reset(&mut delay).expect("reset");
    let r = chip.read_pressure(&mut delay).expect("read");
    check_true!(r > 30000.0 && r < 110000.0, "read_after_reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
