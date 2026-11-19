#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::humidity::HS30xxFull;

esp_app_desc!();

const ADDR: u8 = 0x44;

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

    // Create HS30xx driver, (i2c, ADDR=0x44) → HS30xx
    let mut chip = HS30xxFull::new(i2c, ADDR);
    // Replace with code that cuts and restores the sensor supply (MOSFET or GPIO).
    let mut power_cycle = || {};

    // Read temperature and humidity, (delay) → Result<(f32 °C, f32 %RH)>
    // request, 40 ms wait, 4-byte fetch; one retry on stale data
    let (t, rh) = chip.read(&mut delay).expect("read");
    println!("{} C, {} %RH", t, rh);
    // Read temperature only, (delay) → Result<f32 °C>
    println!("{}", chip.read_temperature(&mut delay).expect("read_temperature"));
    // Read humidity only, (delay) → Result<f32 %RH>
    // 2-byte fetch
    println!("{}", chip.read_humidity(&mut delay).expect("read_humidity"));
    // Read raw values, (delay) → Result<(u16, u16, u8)>
    // (humidity, temperature, status); 14-bit, status 0 = valid, 1 = stale
    println!("{:?}", chip.read_raw(&mut delay).expect("read_raw"));
    // Override the post-request wait, (us) → Result<()>
    // default 40 ms; lower resolutions need less
    chip.set_measurement_wait_us(60_000).expect("set_measurement_wait_us");
    // Compute dew point, (delay) → Result<f32 °C>
    // one measurement, Magnus formula
    println!("{}", chip.dew_point(&mut delay).expect("dew_point"));

    // NVM access only works within 10 ms after power-up, so it needs the power_cycle helper above.
    // Get NVM resolution, (power_cycle, delay) → Result<(u8, u8)>
    // (humidity_bits, temperature_bits)
    println!("{:?}", chip.get_resolution(Some(&mut power_cycle), &mut delay).expect("get_resolution"));
    // Set NVM resolution, (humidity_bits, temperature_bits, power_cycle, delay) → Result<()>
    // 8, 10, 12 or 14 bit; None leaves a channel unchanged
    chip.set_resolution(Some(12), Some(12), Some(&mut power_cycle), &mut delay).expect("set_resolution");
    // Read the 32-bit sensor ID, (power_cycle, delay) → Result<u32>
    println!("{:#x}", chip.read_id(Some(&mut power_cycle), &mut delay).expect("read_id"));
    // Enter programming mode, (delay) → Result<()>
    // only within 10 ms after power-up
    chip.enter_programming_mode(&mut delay).expect("enter_programming_mode");
    // Read an NVM register, (reg, delay) → Result<u16>
    // 0x06 humidity / 0x11 temperature resolution, 0x1E / 0x1F ID
    println!("{:#x}", chip.read_nvm(0x06, &mut delay).expect("read_nvm"));
    // Leave programming mode, () → Result<()>
    chip.exit_programming_mode().expect("exit_programming_mode");

    loop {
        delay.delay_ms(1000);
    }
}
