//! Complete example for the MS5611 — every MS5611Full method once. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::{MS5611Conversion as Conversion, MS5611Full};

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

    // Create MS5611 driver, (i2c, addr=0x77) → MS5611
    let mut chip = MS5611Full::new(i2c, ADDR);
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

    loop {
        sleep_ms!(1000);
    }
}
