#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::{Bmp581Full, IIR_BYPASS, IIR_COEFF_3, OSR_16X, OSR_4X};

esp_app_desc!();

const ADDR: u8 = 0x46;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    // --- Precision altimeter: 10 Hz NORMAL mode for 30 seconds ---
    let mut bmp = Bmp581Full::new(i2c, ADDR, false).expect("init BMP581"); // Create BMP581 driver, (i2c, addr=0x46)
    bmp.configure(0x17, OSR_16X, OSR_4X, true).expect("configure");        // Configure chip, (odr=10Hz, osr_p=×16, osr_t=×4, press_en) → Result<(), E>

    let mut pressures = [0f32; 300];
    let mut temps = [0f32; 300];
    let mut alts = [0f32; 300];
    for n in 0..300 {
        pressures[n] = bmp.pressure().expect("pressure");                  // Read pressure, () → f32 Pa
        temps[n] = bmp.temperature().expect("temperature");                // Read temperature, () → f32 °C
        alts[n] = bmp.altitude(101325.0).expect("altitude");                // Compute altitude, (sea_level_pa=101325.0) → f32 m
        if n % 10 == 0 {
            let start = if n >= 10 { n - 10 } else { 0 };
            let span = if n < 10 { n } else { 10 };
            if span > 0 {
                let mp: f32 = pressures[start..n].iter().sum::<f32>() / span as f32;
                let mt: f32 = temps[start..n].iter().sum::<f32>() / span as f32;
                let ma: f32 = alts[start..n].iter().sum::<f32>() / span as f32;
                println!("{}0s: rolling P={:.1} Pa, T={:.2} C, alt={:.2} m", n / 10, mp, mt, ma);
            }
        }
        delay.delay_ms(100);
    }

    // --- Compare IIR bypass vs IIR coefficient 3 noise floor ---
    let amin = alts.iter().cloned().fold(f32::INFINITY, f32::min);
    let amax = alts.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
    println!("Bypass: alt min={:.3} max={:.3} spread={:.3} m", amin, amax, amax - amin);

    bmp.set_iir_filter(IIR_COEFF_3, IIR_BYPASS).expect("set_iir");         // Set IIR filter, (coeff_p=7-tap, coeff_t=bypass) → Result<(), E>

    let mut alts2 = [0f32; 300];
    for n in 0..300 {
        bmp.pressure().expect("pressure");                                  // Read pressure, () → f32 Pa
        alts2[n] = bmp.altitude(101325.0).expect("altitude");               // Compute altitude, (sea_level_pa=101325.0) → f32 m
        delay.delay_ms(100);
    }
    let amin2 = alts2.iter().cloned().fold(f32::INFINITY, f32::min);
    let amax2 = alts2.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
    println!("IIR=3:  alt min={:.3} max={:.3} spread={:.3} m", amin2, amax2, amax2 - amin2);

    let pmin = pressures.iter().cloned().fold(f32::INFINITY, f32::min);
    let pmax = pressures.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
    let psum: f32 = pressures.iter().sum();
    println!("Min P={:.1}, max P={:.1}, mean P={:.1} Pa", pmin, pmax, psum / pressures.len() as f32);
    loop {}
}
