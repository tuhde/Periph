//! Demo for the HS30xx — hygrometer with raw values, status and dew point.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::HS30xxFull;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x44);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create HS30xx driver, (i2c, addr=0x44) → HS30xx
    let mut chip = HS30xxFull::new(dev, addr);
    // Replace with code that cuts and restores the sensor supply (MOSFET or GPIO).
    #[allow(unused_mut, unused_variables)]
    let mut power_cycle = || {};

    // --- Optional: show and change the stored resolution (set SHOW_RESOLUTION) ---
    // The NVM is only reachable within 10 ms after power-up, so this needs power_cycle().
    const SHOW_RESOLUTION: bool = false;
    if SHOW_RESOLUTION {
        // Get NVM resolution, (power_cycle, delay) → Result<(u8, u8)>
        println!("resolution {:?}", chip.get_resolution(Some(&mut power_cycle), &mut delay).expect("get_resolution"));
        // Set NVM resolution, (humidity_bits, temperature_bits, power_cycle, delay) → Result<()>
        chip.set_resolution(Some(12), Some(12), Some(&mut power_cycle), &mut delay).expect("set_resolution");
        // 12-bit RH + T need about 10 ms, (us) → Result<()>
        chip.set_measurement_wait_us(15_000).expect("set_measurement_wait_us");
    }

    // --- Hygrometer: one reading every 2 s for 30 s ---
    // Raw values and status come from the 14-bit data-fetch frame; status 0 means fresh data.
    println!("t(s)  RH raw  T raw  status  T (C)   RH (%)  Dew (C)");
    for n in 0..15u32 {
        // Read raw values, (delay) → Result<(u16, u16, u8)>
        let (rh_raw, t_raw, status) = chip.read_raw(&mut delay).expect("read_raw");
        // Read temperature and humidity, (delay) → Result<(f32, f32)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        // Dew point, (delay) → Result<f32 °C>
        let dew = chip.dew_point(&mut delay).expect("dew_point");
        println!("{}  {}  {}  {}  {:.2}  {:.1}  {:.2}", n * 2, rh_raw, t_raw, status, t, rh, dew);
        sleep_ms!(2000);
    }
}
