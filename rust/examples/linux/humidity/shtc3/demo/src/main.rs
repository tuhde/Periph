//! Demo for the SHTC3 — battery-friendly climate logger with a normal vs low-power comparison and the dew point.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::SHTC3Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x70);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create SHTC3 driver, (i2c, addr=0x70) → SHTC3
    let mut chip = SHTC3Full::new(dev, addr);
    // Soft reset and ID check, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    // --- Identify the chip ---
    // The ID word carries the SHTC3 product code in bits 11 and 5:0.
    // Read the ID word, (delay) → Result<u16>
    println!("id 0x{:04X}", chip.read_id(&mut delay).expect("id"));

    // --- Battery-friendly climate logger: 6 readings, 10 s apart ---
    // The sensor sleeps (0.3 uA) between the readings; every read wakes it.
    for _ in 0..6 {
        // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %RH)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        println!("{:.2} C  {:.1} %RH", t, rh);
        sleep_ms!(10000);
    }

    // --- Compare normal and low-power mode ---
    // Low power measures in 0.7 ms instead of 10.8 ms at slightly higher noise.
    // Select low-power mode, (on) → ()
    println!("normal    {:?}", chip.read(&mut delay).expect("read"));
    chip.set_low_power(true);
    println!("low power {:?}", chip.read(&mut delay).expect("read"));
    chip.set_low_power(false);

    // --- Dew point and raw words, then finish asleep ---
    // Compute dew point, (delay) → Result<f32 °C>
    println!("dew point {:.2} C", chip.dew_point(&mut delay).expect("dew"));
    // Read raw codes, (delay) → Result<(u16, u16)>
    println!("raw {:?}", chip.read_raw(&mut delay).expect("raw"));
    // Sleep command, () → Result<()>
    chip.sleep().expect("sleep");
}
