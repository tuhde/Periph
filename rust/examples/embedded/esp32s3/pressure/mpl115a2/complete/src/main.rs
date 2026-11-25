#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::Mpl115a2Full;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    let mut baro = Mpl115a2Full::new(i2c, 0x60).expect("init MPL115A2"); // Create MPL115A2 driver, (i2c, addr=0x60) → Result

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
    loop {}
}
