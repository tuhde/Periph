//! Complete example for the MS5607 — every MS5607Full method once.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::pressure::{MS5607Conversion as Conversion, MS5607Full};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x77);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create MS5607 driver, (i2c, addr=0x77) → MS5607
    let mut chip = MS5607Full::new(dev, addr);
    // Reset, read PROM, verify CRC-4, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    // Read temperature and pressure, (delay) → Result<(f32 °C, f32 Pa)>; OSR 4096, second-order compensation
    let (t, p) = chip.read(&mut delay).expect("read");
    println!("{:.2} C, {:.1} Pa", t, p);
    // Read pressure only, (delay) → Result<f32 Pa>
    println!("pressure {:.1} Pa", chip.read_pressure(&mut delay).expect("p"));
    // Read temperature only, (delay) → Result<f32 °C>; converts D2 only
    println!("temperature {:.2} C", chip.read_temperature(&mut delay).expect("t"));
    // Read raw ADC codes, (delay) → Result<(u32, u32)>; (d1 pressure, d2 temperature), 24-bit
    let (d1, d2) = chip.read_raw(&mut delay).expect("raw");
    println!("raw {} {}", d1, d2);
    // Compensate raw codes, (d1, d2, second_order) → (f32 °C, f32 Pa); pure computation, no bus access
    println!("compensated {:?}", chip.compensate(d1, d2, true));
    println!("first order {:?}", chip.compensate(d1, d2, false));
    // Select oversampling ratio, (osr) → Result<()>; 256, 512, 1024, 2048 or 4096
    chip.set_oversampling(1024).expect("osr");
    // Current oversampling ratio, () → u16
    println!("osr {}", chip.get_oversampling());
    // Max conversion time, (osr: Option<u16>) → Result<f32 s>
    println!("conversion time {:.5} s", chip.conversion_time(None).expect("ct"));
    // Start a conversion, (kind, osr: Option<u16>) → Result<()>; non-blocking
    chip.start_conversion(Conversion::Temperature, None).expect("start");
    sleep_ms!(12);
    // Read the ADC result, () → Result<u32>; only after the conversion time, 24-bit
    println!("adc {}", chip.read_adc().expect("adc"));
    chip.set_oversampling(4096).expect("osr");
    // Read the PROM words, () → Result<[u16; 8]>
    println!("prom {:?}", chip.read_prom().expect("prom"));
    // Calibration coefficients, () → [u16; 6]; C1 .. C6
    println!("coefficients {:?}", chip.coefficients());
    // Verify the PROM CRC-4, () → bool
    println!("crc ok {}", chip.prom_crc_ok());
    // Serial code, () → u16; PROM word 7 bits 15:4
    println!("serial {}", chip.serial_code());
    // Altitude from pressure, (delay, sea_level_pa) → Result<f32 m>; international barometric formula
    println!("altitude {:.2} m", chip.altitude(&mut delay, 101325.0).expect("alt"));
    // Reset and reload PROM, (delay) → Result<()>; 0x1E, 3 ms wait
    chip.reset(&mut delay).expect("reset");
}
