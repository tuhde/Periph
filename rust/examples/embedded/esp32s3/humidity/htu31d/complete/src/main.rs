//! Complete example for the HTU31D — every HTU31DFull method once. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::humidity::HTU31DFull;

esp_app_desc!();

const ADDR: u8 = 0x40;

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

    // Create HTU31D driver, (i2c, addr=0x40) → HTU31D
    let mut chip = HTU31DFull::new(i2c, ADDR);
    // Soft reset, (delay) → Result<()>; waits 15 ms, highest resolution, heater off
    chip.init(&mut delay).expect("init");

    // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %RH)>; OSR 3 / 3, both CRCs verified
    let (t, rh) = chip.read(&mut delay).expect("read");
    println!("{:.2} C, {:.1} %RH", t, rh);
    // Read humidity only, (delay) → Result<f32 %RH>; command 0x10
    println!("humidity {:.1} %RH", chip.read_humidity(&mut delay).expect("rh"));
    // Read temperature only, (delay) → Result<f32 °C>
    println!("temperature {:.2} C", chip.read_temperature(&mut delay).expect("t"));
    // Read raw codes, (delay) → Result<(u16, u16)>; (t_raw, rh_raw)
    println!("raw {:?}", chip.read_raw(&mut delay).expect("raw"));

    // Set oversampling, (osr_rh 0-3, osr_t 0-3) → Result<()>; higher OSR = finer resolution, longer conversion
    chip.set_resolution(1, 2).expect("resolution");
    // Get oversampling, () → (u8, u8)
    println!("resolution {:?}", chip.get_resolution());
    // Typical conversion time, () → f32 s
    println!("conversion time {:.4} s", chip.conversion_time());
    chip.set_resolution(3, 3).expect("resolution");

    // Start a conversion, () → Result<()>; non-blocking, wait conversion_time() before reading
    chip.start_conversion().expect("start");
    sleep_ms!(30);
    // Read last conversion result, () → Result<(f32 °C, f32 %RH)>
    println!("result {:?}", chip.read_result().expect("result"));

    // Switch the heater, (on) → Result<()>; 0x04, readings drift while the heater runs
    chip.set_heater(true).expect("heater on");
    // Heater state, () → Result<bool>
    println!("heater on {}", chip.is_heater_on().expect("is_heater_on"));
    chip.set_heater(false).expect("heater off");
    // Read the diagnostic byte, () → Result<u8>; test against the DIAG_* masks
    println!("diagnostic 0x{:02X}", chip.diagnostic().expect("diagnostic"));
    // Read the serial number, () → Result<u32>; 24-bit, CRC verified
    println!("serial 0x{:06X}", chip.serial_number().expect("serial"));
    // Compute dew point, (delay) → Result<f32 °C>; one measurement, Magnus formula
    println!("dew point {:.2} C", chip.dew_point(&mut delay).expect("dew"));
    // Soft reset, (delay) → Result<()>; 0x1E, waits 15 ms; heater off, OSR 3 / 3
    chip.software_reset(&mut delay).expect("reset");

    loop {
        sleep_ms!(1000);
    }
}
