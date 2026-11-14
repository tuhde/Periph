//! Demo for the AM2315 — weather-station logger with dew point, min/max and user registers.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::environmental::AM2315Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create AM2315 driver, (i2c, addr=0x5C) → AM2315
    let mut chip = AM2315Full::new(dev, addr);

    // Read device information, (delay) → Result<(u16, u8, u32)>
    let (model, version, device_id) = chip.read_device_info(&mut delay).expect("read_device_info");
    println!("model {}, version {}, device id 0x{:08X}", model, version, device_id);

    // --- Weather-station logger: a sample every 5 s, min/max tracking ---
    // The sensor measures after a communication and returns that result on the next one,
    // so every sample is the previous measurement; the driver waits 2 s before every
    // transaction and checks the CRC-16.
    let (mut t_min, mut t_max, mut h_min, mut h_max) = (1000.0f32, -1000.0f32, 1000.0f32, -1000.0f32);
    for i in 1..=12 {
        let (t, rh) = if i % 10 == 0 {
            // Real-time value, (delay) → Result<(f32, f32)> — two reads
            chip.read_fresh(&mut delay).expect("read_fresh")
        } else {
            // Read temperature and humidity, (delay) → Result<(f32, f32)>
            chip.read(&mut delay).expect("read")
        };
        t_min = t_min.min(t); t_max = t_max.max(t);
        h_min = h_min.min(rh); h_max = h_max.max(rh);
        // Magnus formula
        let g = (rh.max(0.1) / 100.0).ln() + 17.62 * t / (243.12 + t);
        let dew = 243.12 * g / (17.62 - g);
        println!("#{}  {:.1} C  {:.1} %RH  dew point {:.1} C  (T {:.1}..{:.1}, RH {:.1}..{:.1})", i, t, rh, dew, t_min, t_max, h_min, h_max);
        sleep_ms!(3000);
    }

    // --- User registers as a persistent counter ---
    // Write user registers, (user1, user2, delay) → Result<()> ; Read user registers, (delay) → Result<(u16, u16)>
    chip.write_user_registers(12, 0xBEEF, &mut delay).expect("write_user_registers");
    println!("user registers {:04X?}", chip.read_user_registers(&mut delay).expect("read_user_registers"));
    println!("done");
}
