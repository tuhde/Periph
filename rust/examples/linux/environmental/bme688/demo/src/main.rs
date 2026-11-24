use linux_embedded_hal::I2cdev;
use periph::chips::environmental::{Bme688Full, OSRS_X2, OSRS_X16, OSRS_X1, MODE_SLEEP, FILTER_15};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x76);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    // --- Room air probe with parallel mode ---
    // Part 1: ten forced-mode readings (T, RH, hPa, Ohm, dew point), one per 5 s.
    // Part 2: parallel mode with a 3-step heater profile (320, 250 and 180 degC;
    // repeats 1, 2, 1; shared wait 100 ms) for one minute, printing the gas
    // resistance of every heater step, then back to sleep.
    let mut bme = Bme688Full::new(dev, addr).expect("init BME688"); // Create BME688 driver, (i2c, addr)
    bme.configure(OSRS_X2, OSRS_X16, OSRS_X1, MODE_SLEEP, FILTER_15).expect("configure"); // Configure chip, (osrs_t=×2, osrs_p=×16, osrs_h=×1, mode=sleep, filter=15) → ()
    bme.set_heater(320, 150).expect("set_heater");                 // Configure heater profile 0, (temp_c=320, duration_ms=150) → ()

    println!("--- forced mode ---");
    for n in 0..10u32 {
        let (t, p, h, g) = bme.read_all().expect("read_all");      // Read all sensors in one cycle, () → (f32, f32, f32, f32)
        let gm = (if h > 0.01 { h } else { 0.01 } / 100.0).ln() + 17.62 * t / (243.12 + t);
        let dew = 243.12 * gm / (17.62 - gm);                      // Magnus dew point, °C
        println!("{}: {:.1} C, {:.1} %RH, {:.1} hPa, {:.0} Ohm, dew {:.1} C", n, t, h, p, g, dew);
        std::thread::sleep(std::time::Duration::from_millis(5000));
    }

    println!("--- parallel mode ---");
    bme.start_parallel(&[(320, 1), (250, 2), (180, 1)], 100.0).expect("start_parallel"); // Start parallel mode, (steps=[(temp_c, repeats)], shared_wait_ms) → ()
    for _ in 0..60u32 {
        let fields = bme.read_parallel().expect("read_parallel");  // Read the new data fields, () → [Option<Bme688Field>; 3]
        for f in fields.iter().flatten() {
            println!("step {} #{}: {:.1} C, {:.1} %RH, {:.0} Ohm (valid={}, stable={})",
                     f.step, f.sub_index, f.temperature, f.humidity, f.gas_resistance, f.gas_valid, f.heater_stable);
        }
        std::thread::sleep(std::time::Duration::from_millis(1000));
    }
    bme.stop_parallel().expect("stop_parallel");                   // Back to sleep, () → ()
}
