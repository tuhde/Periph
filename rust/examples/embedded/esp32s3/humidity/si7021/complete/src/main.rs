#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::humidity::Si7021Full;

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

    // Create Si7021 driver, (i2c, ADDR=0x40) → Si7021
    let mut chip = Si7021Full::new(i2c, ADDR);

    // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %RH)>
    // RH without hold master, temperature of the same conversion
    let (t, rh) = chip.read(&mut delay).expect("read");
    println!("{:.2} C, {:.1} %RH", t, rh);
    // Read humidity only, (delay) → Result<f32> %RH
    println!("humidity {:.1} %RH", chip.read_humidity(&mut delay).expect("humidity"));
    // Read temperature (new conversion), (delay) → Result<f32> °C
    println!("temperature {:.2} C", chip.read_temperature(&mut delay).expect("temperature"));
    // Temperature of the last RH measurement, () → Result<f32> °C
    // command 0xE0, no new conversion
    println!("previous {:.2} C", chip.read_temperature_previous().expect("previous"));
    // Read raw codes, (delay) → Result<u16>
    // 16-bit codes, status bits included
    println!("raw {} {}", chip.read_raw_humidity(&mut delay).expect("raw rh"), chip.read_raw_temperature(&mut delay).expect("raw t"));

    // Set resolution, (rh_bits, temperature_bits) → Result<()>
    // (12, 14) / (8, 12) / (10, 13) / (11, 11)
    chip.set_resolution(11, 11).expect("set_resolution");
    // Get resolution, () → Result<(u8, u8)>
    println!("resolution {:?}", chip.get_resolution().expect("get_resolution"));
    // Typical conversion time, () → Result<f32> s
    println!("conversion time {:.4} s", chip.conversion_time().expect("conversion_time"));
    chip.set_resolution(12, 14).expect("set_resolution");

    // Switch the heater, (on) → Result<()>
    // about 3.1 mA; readings drift while on
    chip.set_heater(true).expect("heater on");
    // Heater state, () → Result<bool>
    println!("heater on {}", chip.is_heater_on().expect("is_heater_on"));
    chip.set_heater(false).expect("heater off");
    // Supply low flag, () → Result<bool>
    // true when VDD is below about 1.9 V
    println!("VDD low {}", chip.vdd_low().expect("vdd_low"));
    // Read USER_REG_1, () → Result<u8>
    // 0x3A after reset
    let reg = chip.read_user_register().expect("read_user_register");
    // Write USER_REG_1, (value) → Result<()>
    // reserved bits are preserved
    chip.write_user_register(reg).expect("write_user_register");
    // Use hold-master commands, (on) → ()
    // needs bus-layer support; off polls the NACKed read header
    chip.set_clock_stretching(false);

    // Electronic serial number, () → Result<u64>
    println!("serial number 0x{:016X}", chip.serial_number().expect("serial_number"));
    // Device type, () → Result<u8>
    // 0x15 = Si7021
    println!("device type 0x{:02X}", chip.device_type().expect("device_type"));
    // Firmware revision, () → Result<u8>
    // 0xFF = firmware 1.0
    println!("firmware 0x{:02X}", chip.firmware_revision().expect("firmware_revision"));
    // Compute dew point, (delay) → Result<f32> °C
    // one measurement, Magnus formula
    println!("dew point {:.2} C", chip.dew_point(&mut delay).expect("dew_point"));
    // Software reset, (delay) → Result<()>
    // 0xFE, waits 15 ms
    chip.software_reset(&mut delay).expect("software_reset");

    loop {
        delay.delay_ms(1000);
    }
}
