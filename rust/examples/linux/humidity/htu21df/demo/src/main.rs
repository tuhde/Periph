//! Demo for the HTU21D-F — humidity logger with dew point, resolutions and heater self-check.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::HTU21DFFull;

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

    // Create HTU21D-F driver, (i2c, addr=0x40) → HTU21DF
    let mut chip = HTU21DFFull::new(dev, addr);

    // Soft reset, (delay) → Result<()>
    chip.init(&mut delay).expect("init");
    chip.soft_reset(&mut delay).expect("soft_reset");
    // Read the user register, () → Result<u8> ; Get resolution, () → Result<u8>
    println!("user register 0x{:02X}", chip.read_user_register().expect("read_user_register"));
    println!("resolution {}", chip.get_resolution().expect("get_resolution"));

    // --- Five readings at 12/14 bit ---
    for _ in 0..5 {
        // Read temperature and humidity, (delay) → Result<(f32, f32)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        // Compensated humidity, (delay) → Result<f32> %RH ; Compute dew point, (delay) → Result<f32> °C
        println!("{:.2} C {:.1} %RH (compensated {:.1}) dew point {:.2} C", t, rh,
            chip.read_humidity_compensated(&mut delay).expect("compensated"), chip.dew_point(&mut delay).expect("dew_point"));
        sleep_ms!(2000);
    }
    // Read raw codes, (delay) → Result<(u16, u16)>
    println!("raw {:?}", chip.read_raw(&mut delay).expect("read_raw"));

    // --- 8/12 bit: shorter conversion, coarser resolution ---
    // Set resolution, (res) → Result<()>
    chip.set_resolution(1).expect("set_resolution");
    let start = std::time::Instant::now();
    let t = chip.read_temperature(&mut delay).expect("temperature");
    let rh = chip.read_humidity(&mut delay).expect("humidity");
    println!("8/12 bit: {:.2} C {:.1} %RH in {} ms", t, rh, start.elapsed().as_millis());
    chip.set_resolution(0).expect("set_resolution");
    // Use hold-master commands, (on) → ()
    chip.set_hold_master(false);

    // --- Heater self-check: 10 s ---
    let rh_before = chip.read_humidity(&mut delay).expect("humidity");
    let t_before = chip.read_temperature(&mut delay).expect("temperature");
    // Switch the heater, (on) → Result<()>
    chip.set_heater(true).expect("heater on");
    sleep_ms!(10000);
    // Heater state, () → Result<bool>
    println!("heater on {}", chip.heater().expect("heater"));
    println!("RH drop {:.1} %RH, temperature rise {:.2} K",
        rh_before - chip.read_humidity(&mut delay).expect("humidity"),
        chip.read_temperature(&mut delay).expect("temperature") - t_before);
    chip.set_heater(false).expect("heater off");
    // End-of-battery flag, () → Result<bool>
    println!("battery low {}", chip.battery_low().expect("battery_low"));
}
