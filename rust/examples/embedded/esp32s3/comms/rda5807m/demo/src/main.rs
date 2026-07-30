#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::comms::RDA5807MFull;

esp_app_desc!();

const ADDR: u8 = 0x10;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut fm = RDA5807MFull::new(i2c, ADDR, 87.5, 10).expect("init RDA5807M");

    // --- FM band scanner ---
    // Start at the bottom of the world-wide band and repeatedly seek upward
    // with SKMODE=1 (stop at band limit, the Minimal/Full default) so a seek
    // that returns None means the top of the band has been reached.
    fm.enable_rds(true).unwrap();

    println!("Scanning...");
    let mut count = 0;
    loop {
        let freq = match fm.seek(true).unwrap() {
            Some(f) => f,
            None => break,
        };
        if !fm.is_station().unwrap() {
            continue;
        }

        let rssi = fm.signal_strength().unwrap();
        let stereo = fm.is_stereo().unwrap();

        // --- Try to read the Program Service (station) name via RDS ---
        // Group types 0A/0B carry the 8-character PS name, four segments of
        // two characters each, addressed by block B bits 1:0. Give the
        // decoder up to 2 seconds to assemble a full name.
        let mut ps_chars: [Option<char>; 8] = [None; 8];
        let deadline = Instant::now() + Duration::from_secs(2);
        while Instant::now() < deadline {
            if fm.rds_ready().unwrap() {
                if let Some((_, block_b, _, block_d)) = fm.read_rds_group().unwrap() {
                    let group_type = block_b >> 12;
                    let is_b_variant = (block_b >> 11) & 1;
                    if group_type == 0 && is_b_variant == 0 {
                        let segment = (block_b & 0x03) as usize;
                        ps_chars[segment * 2] = Some((block_d >> 8) as u8 as char);
                        ps_chars[segment * 2 + 1] = Some((block_d & 0xFF) as u8 as char);
                        if ps_chars.iter().all(|c| c.is_some()) {
                            break;
                        }
                    }
                }
            }
            delay.delay_ms(40);
        }

        let name: String = if ps_chars.iter().all(|c| c.is_some()) {
            ps_chars.iter().map(|c| c.unwrap()).collect()
        } else {
            "(no RDS name)".to_string()
        };

        println!("{:.2} MHz  RSSI={}  {}  {}", freq, rssi, if stereo { "stereo" } else { "mono" }, name.trim());
        count += 1;
    }

    println!();
    println!("Scan complete: {} station(s) found", count);
}
