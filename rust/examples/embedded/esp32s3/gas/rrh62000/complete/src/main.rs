//! Complete example for the RRH62000 — every RRH62000Full method once. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
#[allow(unused_imports)]
use esp_println::{print, println};
use periph::chips::gas::Rrh62000Full;

esp_app_desc!();

const ADDR: u8 = 0x69;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let bus = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    #[allow(unused_mut)]
    let mut delay = Delay::new();
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }
    let addr = ADDR;

    // Create RRH62000 driver, (i2c, delay, addr=0x69) → Rrh62000Full
    let mut chip = Rrh62000Full::new(bus, Delay::new(), addr);
    // The driver owns the delay: it waits 1 s before every access (tRD) and 2.3 s more before the first block.
    // Wait for start-up, check firmware version, () → Result<()>
    chip.init().expect("init");
    // Reads the firmware version as a presence check.

    // Firmware version, () → Result<(major, minor)>
    println!("firmware {:?}", chip.firmware_version());
    // Two bytes from 0x75.
    // TVOC algorithm version, () → Result<(major, minor, patch)>
    println!("algorithm {:?}", chip.algorithm_version());
    // Version of the ZMOD4410 algorithm in the module.
    // Unique ID, () → Result<[u8; 6]>
    println!("unique ID {:02X?}", chip.unique_id());
    // Identifies the individual module.

    // Main values, () → Result<Measurement>
    println!("{:?}", chip.read());
    // Waits for DATA, reads the 37-byte block, checks the CRC; PM uses the KCl reference.
    // Complete block, () → Result<Data>
    println!("{:?}", chip.read_all());
    // Status, 5 number-concentration bins (cm⁻³), PM KCl and smoke, T, RH, TVOC, eCO₂, IAQ.
    // Status flags, () → Result<Status {raw, high_concentration, dust_accumulation, fan_speed, fan}>
    println!("{:?}", chip.read_status());
    // Reads the current block without waiting for new data.
    // Particle values, () → Result<Particles>
    println!("{:?}", chip.read_particles());
    // PM1/2.5/10 (KCl and cigarette smoke, µg/m³) and the number bins (cm⁻³).
    // New sample available, () → Result<bool>
    println!("data ready {:?}", chip.data_ready());
    // DATA (0x40); a sample arrives every 3 s.

    // Moving average, (count 1–60) → Result<()>
    chip.set_moving_average(20).ok();
    // More averages: smoother values, slower response (24 s at 10).
    // Moving average, () → Result<u8>
    println!("moving average {:?}", chip.get_moving_average());
    // 20.
    // Fan speed, (percent 60–100) → Result<()>
    chip.set_fan_speed(80).ok();
    // Lower speed is quieter; the default is 86 %.
    // Fan speed, () → Result<u8>
    println!("fan {:?} %", chip.get_fan_speed());
    // 80.
    // Cleaning interval, (seconds, multiple of 30) → Result<()>
    chip.set_cleaning_interval(3 * 24 * 3600).ok();
    // Written as a 16-bit count of 30 s units, high byte first.
    // Cleaning interval, () → Result<u32 s>
    println!("cleaning interval {:?} s", chip.get_cleaning_interval());
    // 259200.
    // Cleaning time, (seconds 0–60) → Result<()>
    chip.set_cleaning_time(15).ok();
    // How long the fan runs at full speed during the automatic cleaning.
    // Cleaning time, () → Result<u8 s>
    println!("cleaning time {:?} s", chip.get_cleaning_time());
    // 15.

    // Start fan cleaning, () → Result<()>
    chip.start_cleaning().ok();
    // Blows dust out of the air channel.
    sleep_ms!(15000);
    // Cleaning status, () → Result<bool>
    println!("cleaning done {:?}", chip.cleaning_done());
    // CSTATUS (0x74): true when completed.
    // MOX resistance, () → Result<u32 Ω>
    println!("MOX {:?} ohm", chip.read_mox_resistance());
    // Raw resistance of the TVOC sensing element.

    // Sleep, () → Result<()>
    chip.sleep().ok();
    // Stops the fan and the measurement.
    sleep_ms!(5000);
    // Wake up, () → Result<()>
    chip.wake().ok();
    // The next measurement read waits for valid data (3.3 s).
    // Reset, () → Result<()>
    chip.soft_reset().ok();
    // Same as power-on.
    // Main values, () → Result<Measurement>
    println!("after reset {:?}", chip.read());
    // First sample after the reset.

    loop {
        sleep_ms!(1000);
    }
}
