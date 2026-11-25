//! Demo for the MS5607 — barometric altimeter and variometer, oversampling comparison and the second-order correction. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::{MS5607Conversion as Conversion, MS5607Full};

esp_app_desc!();

const ADDR: u8 = 0x77;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    #[allow(unused_mut)]
    let mut delay = Delay::new();
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }

    // Create MS5607 driver, (i2c, addr=0x77) → MS5607
    let mut chip = MS5607Full::new(i2c, ADDR);
    // Reset, read PROM, verify CRC-4, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    // --- Calibration data ---
    // Six coefficients and a CRC-4 are factory-programmed into the PROM.
    // Read the PROM words, () → Result<[u16; 8]>
    println!("PROM {:?}", chip.read_prom().expect("prom"));
    // Verify the PROM CRC-4, () → bool
    println!("CRC ok {}", chip.prom_crc_ok());
    // Calibration coefficients, () → [u16; 6]
    println!("coefficients {:?}", chip.coefficients());
    println!("serial {}", chip.serial_code());

    // --- Barometric altimeter / variometer: 20 s, one reading per second ---
    // Read pressure only, (delay) → Result<f32 Pa>
    let reference = chip.read_pressure(&mut delay).expect("p");
    let mut previous = 0.0f32;
    let mut lowest = 100.0f32;
    for i in 0..20 {
        // Read temperature and pressure, (delay) → Result<(f32 °C, f32 Pa)>
        let (t, p) = chip.read(&mut delay).expect("read");
        if t < lowest { lowest = t; }
        // Altitude from pressure, (delay, sea_level_pa) → Result<f32 m>; relative to the first reading
        let height = chip.altitude(&mut delay, reference).expect("alt");
        let climb = if i == 0 { 0.0 } else { height - previous };
        previous = height;
        println!("{:.2} C  {:.1} Pa  {:.2} m  {:+.2} m/s", t, p, height, climb);
        sleep_ms!(1000);
    }
    // Altitude from pressure, (delay, sea_level_pa) → Result<f32 m>
    println!("altitude vs standard sea level {:.1} m", chip.altitude(&mut delay, 101325.0).expect("alt"));

    // --- Noise versus oversampling: ten readings each ---
    for osr in [256u16, 512, 1024, 2048, 4096] {
        // Select oversampling ratio, (osr) → Result<()>
        chip.set_oversampling(osr).expect("osr");
        let mut lo = f32::MAX;
        let mut hi = f32::MIN;
        for _ in 0..10 {
            let v = chip.read_pressure(&mut delay).expect("p");
            if v < lo { lo = v; }
            if v > hi { hi = v; }
        }
        println!("OSR {}  spread {:.1} Pa", osr, hi - lo);
    }
    chip.set_oversampling(4096).expect("osr");

    // --- Raw codes with and without the second-order correction ---
    // Read raw ADC codes, (delay) → Result<(u32, u32)>
    let (d1, d2) = chip.read_raw(&mut delay).expect("raw");
    println!("raw {} {}", d1, d2);
    // Compensate raw codes, (d1, d2, second_order) → (f32 °C, f32 Pa)
    println!("first order  {:?}", chip.compensate(d1, d2, false));
    println!("second order {:?}", chip.compensate(d1, d2, true));
    println!("lowest temperature seen {:.2} C", lowest);

    // --- Non-blocking conversion, then reset ---
    // Start a conversion, (kind, osr: Option<u16>) → Result<()>
    chip.start_conversion(Conversion::Pressure, None).expect("start");
    sleep_ms!(12);
    // Read the ADC result, () → Result<u32>
    println!("D1 {}", chip.read_adc().expect("adc"));
    // Reset and reload PROM, (delay) → Result<()>
    chip.reset(&mut delay).expect("reset");

    loop {
        sleep_ms!(1000);
    }
}
