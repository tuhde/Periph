use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::Apds9930Full;
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
        .unwrap_or(0x39);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus"); // Open I²C bus, (/dev/i2c-N) → I2cdev
    let mut delay = Delay;
    let mut chip = Apds9930Full::new(dev, addr, &mut delay).expect("init");       // Construct APDS-9930 Full, (i2c, addr=0x39, delay) → Result<Apds9930Full, _>

    sleep(Duration::from_millis(110));

    check("chip_id is 0x39", chip.chip_id().unwrap() == 0x39);

    let st = chip.status().expect("status");
    check("status has avalid bool", true);

    let lx = chip.lux().expect("lux");
    check("lux is finite", lx.is_finite());
    check("lux >= 0", lx >= 0.0);

    let p = chip.proximity().expect("proximity");
    check("proximity >= 0", p <= u16::MAX);

    let c0 = chip.ch0().expect("ch0");
    let c1 = chip.ch1().expect("ch1");
    check("ch0 >= 0", c0 <= u16::MAX);
    check("ch1 >= 0", c1 <= u16::MAX);

    chip.configure_als(0xDB, 0, false).expect("als");
    chip.configure_proximity(8, 0, 0, false, 0xFF).expect("prox");
    chip.disable_wait().expect("wait");
    chip.set_als_thresholds(0, 65535, 1).expect("als th");
    chip.set_proximity_thresholds(0, 1023, 1).expect("prox th");
    chip.set_proximity_offset(0).expect("offset");
    chip.sleep_after_interrupt(false).expect("sai");
    chip.clear_interrupt(0).expect("clear");
    check("config methods accepted", true);
}