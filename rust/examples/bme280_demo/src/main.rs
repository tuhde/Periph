use linux_embedded_hal::I2cdev;
use periph::chips::environmental::{
    Bme280Full, OSRS_X1, MODE_FORCED, FILTER_OFF, T_SB_0_5_MS,
};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x76);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    // --- Weather monitoring preset: forced mode, ×1/×1/×1, filter off ---
    // BME280 datasheet "weather monitoring" preset: minimum power,
    // single-shot, 8 ms typ / 9.3 ms max per cycle. Sleep between samples
    // to demonstrate battery-friendly indoor monitoring.
    let mut bme = Bme280Full::new(dev, addr, false).expect("init BME280"); // Create BME280 driver, (i2c, addr=0x76, spi=false)
    bme.configure(OSRS_X1, OSRS_X1, OSRS_X1, MODE_FORCED, FILTER_OFF, T_SB_0_5_MS).expect("configure");  // Configure chip, (osrs_t=×1, osrs_p=×1, osrs_h=×1, mode=forced, filter=off, t_sb=0) → ()

    let mut temps = Vec::new();
    let mut hums = Vec::new();
    let mut pressures = Vec::new();
    let mut alts = Vec::new();
    let mut dews = Vec::new();
    for n in 0..10 {
        let t = bme.temperature().expect("read temperature");          // Read temperature, () → f32 °C
        let p = bme.pressure().expect("read pressure");                // Read pressure, () → f32 hPa
        let h = bme.humidity().expect("read humidity");                // Read humidity, () → f32 %RH
        let a = bme.altitude(1013.25).expect("altitude");             // Compute altitude, (sea_level_hpa=1013.25) → f32 m
        let d = bme.dew_point().expect("dew point");                   // Compute dew point, () → f32 °C
        temps.push(t); hums.push(h); pressures.push(p); alts.push(a); dews.push(d);
        println!("{}: {:.1} C, {:.1} %RH, {:.1} hPa, dew={:.1} C, alt={:.1} m", n, t, h, p, d, a);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }

    // --- Half-way: breathe gently on the sensor for 3 seconds ---
    // User exposes the sensor to humid exhaled air; humidity climbs from
    // ~40 %RH toward ~80 %RH, dew point spikes toward ambient temperature,
    // pressure stays flat, temperature rises only slightly. Demonstrates
    // the humidity channel's response and the dew-point alarm use case.
    println!("--- Breathe gently on the sensor for 3 seconds ---");
    std::thread::sleep(std::time::Duration::from_secs(3));
    {
        let t = bme.temperature().expect("read temperature");          // Read temperature, () → f32 °C
        let p = bme.pressure().expect("read pressure");                // Read pressure, () → f32 hPa
        let h = bme.humidity().expect("read humidity");                // Read humidity, () → f32 %RH
        let d = bme.dew_point().expect("dew point");                   // Compute dew point, () → f32 °C
        temps.push(t); hums.push(h); pressures.push(p); dews.push(d);
        println!("after breath: {:.1} C, {:.1} %RH, {:.1} hPa, dew={:.1} C", t, h, p, d);
    }

    let stats = |v: &[f32]| -> (f32, f32, f32) {
        let n = v.len() as f32;
        let sum: f32 = v.iter().sum();
        let mean = sum / n;
        let min = v.iter().cloned().fold(f32::INFINITY, f32::min);
        let max = v.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
        (min, mean, max)
    };

    let (t_min, t_avg, t_max) = stats(&temps);
    let (h_min, h_avg, h_max) = stats(&hums);
    let (p_min, p_avg, p_max) = stats(&pressures);
    let (d_min, d_avg, d_max) = stats(&dews);
    println!("T:    {:.1}/{:.1}/{:.1} C", t_min, t_avg, t_max);
    println!("RH:   {:.1}/{:.1}/{:.1} %", h_min, h_avg, h_max);
    println!("P:    {:.1}/{:.1}/{:.1} hPa", p_min, p_avg, p_max);
    println!("dew:  {:.1}/{:.1}/{:.1} C", d_min, d_avg, d_max);
}
