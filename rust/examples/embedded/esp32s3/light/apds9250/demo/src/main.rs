//! Demo for the APDS-9250 — colour sampler with gain/resolution steps and polled interrupts. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::light::{Apds9250Full, Apds9250Channel, APDS9250_SOURCE_THRESHOLD, APDS9250_SOURCE_VARIATION, APDS9250_STATUS_INTERRUPT, APDS9250_I2C_ADDRESS};

esp_app_desc!();

const ADDR: u8 = APDS9250_I2C_ADDRESS;

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

    // Create APDS-9250 Full driver, (i2c, addr=0x52) → Apds9250Full
    let mut chip = Apds9250Full::new(i2c, ADDR);
    // Check PART_ID and start colour mode, (delay) → Result<(), Apds9250Error>
    chip.init(&mut delay).expect("init");

    // --- Colour sampler ---
    // Prints the part ID and settings, samples R/G/B/IR as a normalised colour,
    // compares ALS/IR mode, steps gain and resolution with normalised counts,
    // arms a variation and a threshold interrupt on the green channel (polled
    // through poll_interrupt), then disables the sensor and soft-resets it.
    println!("part/revision {:?}", chip.part_id().expect("part_id"));
    println!(
        "resolution {} bit, rate {} ms, gain {}",
        chip.get_resolution().expect("get_resolution"),
        chip.get_measurement_rate().expect("get_measurement_rate"),
        chip.get_gain().expect("get_gain")
    );
    for _ in 0..20 {
        // Read the four channels, (delay) → Result<Apds9250Counts>
        let c = chip.read(&mut delay).expect("read");
        let top = c.red.max(c.green).max(c.blue).max(1) as u64;
        println!(
            "#{:02x}{:02x}{:02x} IR {}",
            c.red as u64 * 255 / top,
            c.green as u64 * 255 / top,
            c.blue as u64 * 255 / top,
            c.ir
        );
        sleep_ms!(500);
    }

    // Select ALS/IR mode, (colour) → Result<()>
    chip.set_mode(false).expect("set_mode");
    println!("ALS/IR green {} ir {}", chip.read_green().expect("read_green"), chip.read_ir().expect("read_ir"));
    chip.set_mode(true).expect("set_mode");

    for gain in [1u8, 3, 6, 9, 18] {
        // Set the gain, (gain) → Result<(), Apds9250Error>
        chip.set_gain(gain).expect("set_gain");
        sleep_ms!(300);
        let g = chip.read_green().expect("read_green");
        println!("gain {} green {} normalized {:.0}", gain, g, chip.normalized(g).expect("normalized"));
    }
    chip.set_gain(3).expect("set_gain");
    for bits in [13u8, 16, 17, 18, 19, 20] {
        // Set the resolution, (bits) → Result<(), Apds9250Error>
        chip.set_resolution(bits).expect("set_resolution");
        sleep_ms!(500);
        let g = chip.read_green().expect("read_green");
        println!("{} bit full scale {} green {} normalized {:.0}", bits, (1u32 << bits) - 1, g, chip.normalized(g).expect("normalized"));
    }
    chip.set_resolution(18).expect("set_resolution");
    chip.set_measurement_rate(100).expect("set_measurement_rate");

    // Choose the channel, (Apds9250Channel) → Result<()>
    chip.set_interrupt_channel(Apds9250Channel::Green).expect("set_interrupt_channel");
    chip.set_persistence(2).expect("set_persistence");
    chip.set_variance(64).expect("set_variance");
    // Enable the variation interrupt, (source) → Result<()>
    chip.enable_interrupt(APDS9250_SOURCE_VARIATION).expect("enable_interrupt");
    for _ in 0..20 {
        sleep_ms!(500);
        // Read and clear MAIN_STATUS, () → Result<u8>
        let status = chip.poll_interrupt().expect("poll_interrupt");
        if status & APDS9250_STATUS_INTERRUPT != 0 {
            println!("variation interrupt, green {}", chip.read_green().expect("read_green"));
        }
    }

    let base = chip.read_green().expect("read_green");
    // Set the window, (low, high) → Result<(), Apds9250Error>
    chip.set_thresholds(base - base / 4, base + base / 4).expect("set_thresholds");
    chip.enable_interrupt(APDS9250_SOURCE_THRESHOLD).expect("enable_interrupt");
    sleep_ms!(5000);
    println!("status 0x{:02X}", chip.poll_interrupt().expect("poll_interrupt"));
    chip.disable_interrupt().expect("disable_interrupt");
    // Standby, () → Result<()>
    chip.disable().expect("disable");
    // Software reset, (delay) → Result<()>
    chip.soft_reset(&mut delay).expect("soft_reset");

    loop {
        sleep_ms!(1000);
    }
}
