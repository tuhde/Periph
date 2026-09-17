use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::other::{Mpr121Full, SOURCE_OOR};
use std::thread::sleep;
use std::time::Duration;

fn check(label: &str, cond: bool) {
    if cond { println!("PASS {}", label); }
    else    { println!("FAIL {}", label); }
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5A);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus"); // Open I²C bus, (/dev/i2c-N) → I2cdev
    let mut delay = Delay;
    let mut chip = Mpr121Full::new(dev, addr, &mut delay).expect("init");         // Construct MPR121 Full, (i2c, addr=0x5A, delay) → Result<Mpr121Full, _>

    let t = chip.touched().expect("touched");
    check("touched in 0..4095", t <= 0xFFF);

    let f0 = chip.filtered(0).expect("filtered");
    check("filtered(0) in 0..1023", f0 <= 1023);

    let b0 = chip.baseline(0).expect("baseline");
    check("baseline(0) in 0..1023", b0 <= 1023);

    let oor = chip.oor_status().expect("oor");
    check("oor_status in 0..8191", oor <= 0x1FFF);

    chip.stop().expect("stop");
    chip.configure_thresholds(0, 15, 8).expect("thresholds");
    chip.configure_all_thresholds(12, 6).expect("all thresholds");
    chip.configure_proximity_thresholds(8, 4).expect("prox thresholds");
    chip.configure_baseline_filter(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0).expect("baseline filter");
    chip.configure_sampling(16, 1, 0, 0, 4).expect("sampling");
    chip.configure_debounce(1, 1).expect("debounce");
    chip.configure_autoconfig(3300, 0, false, true, true).expect("autoconfig");
    check("configuration methods accepted", true);

    chip.enable_interrupt(SOURCE_OOR).expect("enable int");
    chip.disable_interrupt(SOURCE_OOR).expect("disable int");
    chip.clear_overcurrent().expect("clear ovrc");
    check("interrupt API accepted", true);

    chip.reset(&mut delay).expect("reset");
    check("reset completed", true);

    println!("===DONE===");
    let _ = sleep(Duration::from_millis(10));
}
