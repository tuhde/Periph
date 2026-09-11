use linux_embedded_hal::I2cdev;
use periph::chips::pressure::Lps22dfFull;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    // --- Indoor altimeter preset: 25 Hz, 4-sample average, low-pass filter ---
    // Low-pass at ODR/9 smooths short-term pressure noise (door slams, fans);
    // 4-sample averaging trims noise without adding visible lag.
    let mut lps = Lps22dfFull::new(dev, addr, false).expect("init LPS22DF"); // Create LPS22DF driver, (i2c, addr=0x5C)
    lps.configure(4, 0, true, 1, true).expect("configure");            // Configure chip, (odr=25 Hz, avg=4, en_lpfp=true, lfpf_cfg=ODR/9, bdu=true) → ()

    // --- Baseline capture: 2-second stabilization then zero the altimeter ---
    std::thread::sleep(std::time::Duration::from_secs(2));
    let baseline_p = lps.pressure().expect("baseline pressure");       // Read pressure, () → f32 Pa
    println!("Baseline: {:.0} Pa", baseline_p);

    let mut pressures = Vec::new();
    let mut temps = Vec::new();
    let mut deltas = Vec::new();
    for n in 0..30 {
        let p = lps.pressure().expect("pressure");                      // Read pressure, () → f32 Pa
        let t = lps.temperature().expect("temperature");                // Read temperature, () → f32 °C
        let d = lps.altitude(baseline_p).expect("altitude");           // Compute altitude, (sea_level_pa=baseline_p) → f32 m
        pressures.push(p);
        temps.push(t);
        deltas.push(d);
        println!("{}s: {:.0} Pa, T={:.2} C, dalt={:.3} m", n, p, t, d);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
    let p_min = pressures.iter().cloned().fold(f32::INFINITY, f32::min);
    let p_max = pressures.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
    let p_mean: f32 = pressures.iter().sum::<f32>() / pressures.len() as f32;
    let t_min = temps.iter().cloned().fold(f32::INFINITY, f32::min);
    let t_max = temps.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
    let t_mean: f32 = temps.iter().sum::<f32>() / temps.len() as f32;
    let d_min = deltas.iter().cloned().fold(f32::INFINITY, f32::min);
    let d_max = deltas.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
    let d_mean: f32 = deltas.iter().sum::<f32>() / deltas.len() as f32;
    println!("P min={:.0} max={:.0} mean={:.1} Pa", p_min, p_max, p_mean);
    println!("T min={:.2} max={:.2} mean={:.2} C", t_min, t_max, t_mean);
    println!("dalt min={:.3} max={:.3} mean={:.3} m", d_min, d_max, d_mean);
}