#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::humidity::HDC1080Full;

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

    // Create HDC1080 driver, (i2c, addr=0x40) → HDC1080
    let mut chip = HDC1080Full::new(i2c, ADDR);

    // Write default configuration, () → Result<()>; 0x1000: 14-bit T and RH, sequence mode, heater off
    chip.init().expect("init");
    // Read the 40-bit serial ID, () → Result<u64>
    println!("serial id 0x{:010X}", chip.serial_id().expect("serial_id"));
    // Read the configuration register, () → Result<u16>
    println!("config 0x{:04X}", chip.read_config().expect("read_config"));
    // Read temperature and humidity, (delay) → Result<(f32, f32)> °C, %RH; sequence mode
    let (t, rh) = chip.read(&mut delay).expect("read");
    println!("{:.2} C, {:.1} %RH", t, rh);
    // Read temperature only, (delay) → Result<f32> °C; single mode
    println!("temperature {:.2} C", chip.read_temperature(&mut delay).expect("temperature"));
    // Read humidity only, (delay) → Result<f32> %RH; single mode
    println!("humidity {:.1} %RH", chip.read_humidity(&mut delay).expect("humidity"));
    // Read raw words, (delay) → Result<(u16, u16)>; (temperature, humidity), bits 1:0 zero
    println!("raw {:?}", chip.read_raw(&mut delay).expect("read_raw"));

    // Set resolution, (temperature_bits, humidity_bits) → Result<()>; T 14/11 bit, RH 14/11/8 bit
    chip.set_resolution(11, 8).expect("set_resolution");
    // Conversion time, () → f32 s; typical T + RH time
    println!("conversion time {:.2} ms", chip.conversion_time() * 1000.0);
    chip.set_resolution(14, 14).expect("set_resolution");

    // Switch the heater, (on) → Result<()>; runs only during measurements, readings drift while on
    chip.set_heater(true).expect("heater on");
    // Heater state, () → Result<bool>
    println!("heater on {}", chip.is_heater_on().expect("is_heater_on"));
    chip.set_heater(false).expect("heater off");
    // Battery flag, () → Result<bool>; true when VDD is below 2.8 V
    println!("battery low {}", chip.battery_low().expect("battery_low"));
    // Software reset, (delay) → Result<()>; sets RST, waits 15 ms, rewrites the stored configuration
    chip.software_reset(&mut delay).expect("software_reset");

    // Manufacturer ID, () → Result<u16>; 0x5449 = Texas Instruments
    println!("manufacturer 0x{:04X}", chip.manufacturer_id().expect("manufacturer_id"));
    // Device ID, () → Result<u16>; 0x1050 = HDC1080
    println!("device 0x{:04X}", chip.device_id().expect("device_id"));

    loop {
        delay.delay_ms(1000);
    }
}
