use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::pressure::Mpl115a2Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut baro = Mpl115a2Full::new(dev, 0x60).expect("init MPL115A2"); // Create MPL115A2 driver, (i2c, addr=0x60) → Result

    let (a0, b1, b2, c12) = baro.coefficients();                              // Read cached coefficients, () → (f32, f32, f32, f32)
    println!("a0={:.3} b1={:.5} b2={:.5} c12={:.8}", a0, b1, b2, c12);

    // --- Barometer log ---
    // Reads the pressure every 2 s for 30 s, printing Pa, hPa, approximate
    // altitude and the raw counts, then the min/max of the run.
    let mut pmin = f32::MAX;
    let mut pmax = 0.0f32;
    for n in 0..15 {
        let pa = baro.read_pressure(&mut delay).expect("read pressure");      // Read pressure, (delay) → f32 Pa
        let hpa = baro.pressure_hpa(&mut delay).expect("hpa");                // Read pressure, (delay) → f32 hPa
        let alt = baro.altitude(101325.0, &mut delay).expect("altitude");     // Compute altitude, (sea_level_pa, delay) → f32 m
        let (padc, tadc) = baro.read_raw(&mut delay).expect("read raw");      // One conversion, (delay) → (u16, u16)
        pmin = pmin.min(pa);
        pmax = pmax.max(pa);
        println!("{}s: {:.0} Pa, {:.2} hPa, alt={:.1} m, raw=({}, {})", n * 2, pa, hpa, alt, padc, tadc);
        std::thread::sleep(std::time::Duration::from_secs(2));
    }
    println!("Summary: min={:.0} Pa, max={:.0} Pa", pmin, pmax);
}
