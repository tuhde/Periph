//! Demo for the HDC1008 — humidity logger with dew point, resolutions and heater self-check.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::HDC1008Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create HDC1008 driver, (i2c, addr=0x40) → HDC1008
    let mut chip = HDC1008Full::new(dev, addr);

    // Write default configuration, () → Result<()>
    chip.init().expect("init");
    // Read the 40-bit serial ID, () → Result<u64>
    println!("serial id 0x{:010X}", chip.serial_id().expect("serial_id"));

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    for _ in 0..15 {
        // Read temperature and humidity, (delay) → Result<(f32, f32)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        let rh_c = if rh < 0.01 { 0.01 } else { rh };
        let g = libm_ln(rh_c / 100.0) + 17.62 * t / (243.12 + t);
        let dew = 243.12 * g / (17.62 - g); // Magnus dew point, °C
        println!("{:.2} C {:.1} %RH dew point {:.2} C", t, rh, dew);
        sleep_ms!(2000);
    }

    // --- Heater test: 10 s ---
    let rh0 = chip.read_humidity(&mut delay).expect("rh");
    let t0 = chip.read_temperature(&mut delay).expect("t");
    // Switch the heater, (on) → Result<()>
    chip.set_heater(true).expect("heater on");
    sleep_ms!(10000);
    let rh1 = chip.read_humidity(&mut delay).expect("rh");
    let t1 = chip.read_temperature(&mut delay).expect("t");
    println!("RH drop {:.1} %RH, temperature rise {:.2} K", rh0 - rh1, t1 - t0);
    chip.set_heater(false).expect("heater off");

    // --- Resolution comparison ---
    // Set resolution, (temperature_bits, humidity_bits) → Result<()>
    chip.set_resolution(11, 8).expect("set_resolution");
    let (t, rh) = chip.read(&mut delay).expect("read");
    println!("11/8 bit: {:.2} C {:.1} %RH (conversion {:.2} ms)", t, rh, chip.conversion_time() * 1000.0);
    chip.set_resolution(14, 14).expect("set_resolution");
    // Battery flag, () → Result<bool>
    println!("battery low {}", chip.battery_low().expect("battery_low"));
}

/// Natural logarithm without libm (no_std friendly series, accurate to a few 1e-5 for the demo's range).
fn libm_ln(x: f32) -> f32 {
    let mut m = x;
    let mut e = 0i32;
    while m > 1.5 {
        m /= 2.0;
        e += 1;
    }
    while m < 0.75 {
        m *= 2.0;
        e -= 1;
    }
    let y = (m - 1.0) / (m + 1.0);
    let y2 = y * y;
    2.0 * y * (1.0 + y2 / 3.0 + y2 * y2 / 5.0 + y2 * y2 * y2 / 7.0) + e as f32 * core::f32::consts::LN_2
}
