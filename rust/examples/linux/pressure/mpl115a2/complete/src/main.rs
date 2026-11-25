use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::pressure::Mpl115a2Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut baro = Mpl115a2Full::new(dev, 0x60).expect("init MPL115A2"); // Create MPL115A2 driver, (i2c, addr=0x60) → Result

    let (a0, b1, b2, c12) = baro.coefficients();                              // Read cached coefficients, () → (f32, f32, f32, f32)
    baro.reload_coefficients().expect("reload");                              // Re-read coefficient block, () → Result
                                                                              // use after a power cycle
    let (pa, tc) = baro.read(&mut delay).expect("read");                      // One conversion, (delay) → (f32 Pa, f32 C)
    let (padc, tadc) = baro.read_raw(&mut delay).expect("read raw");          // One conversion, (delay) → (u16, u16) 10-bit counts
    let p = baro.compensate(padc, tadc);                                      // Compensate raw counts, (padc, tadc) → f32 Pa
                                                                              // pure function, no bus access
    let hpa = baro.pressure_hpa(&mut delay).expect("hpa");                    // Read pressure, (delay) → f32 hPa
    let alt = baro.altitude(101325.0, &mut delay).expect("altitude");         // Compute altitude, (sea_level_pa, delay) → f32 m
                                                                              // barometric formula, informative
    println!("a0={:.3} b1={:.5} b2={:.5} c12={:.8}", a0, b1, b2, c12);
    println!("{:.0} Pa, {:.1} C, raw=({}, {}), {:.0} Pa, {:.2} hPa, alt={:.1} m", pa, tc, padc, tadc, p, hpa, alt);
}
