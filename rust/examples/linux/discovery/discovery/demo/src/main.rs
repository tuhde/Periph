use linux_embedded_hal::I2cdev;
use periph::discovery::{discover, ProbeSkipReason};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let mut dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    // --- Take inventory of an unknown bench setup ---
    // Scan /dev/i2c-1 and name everything that answers. A chip is only named when
    // its identity register matches exactly one registry entry; shared addresses
    // without an ID register stay as candidate lists.
    let devices = discover(&mut dev, false).expect("discover"); // Discover chips, (i2c, active=false) → Vec<DiscoveredDevice>

    // --- Report what was found ---
    // identified: confirmed chip; candidates: could be any of these; empty means the
    // address answers but the registry does not know it.
    for d in &devices {
        match (d.identified, d.in_use_by_kernel, d.candidates.is_empty()) {
            (Some(id), _, _) => println!("0x{:02X}  {} (driver: {:?})", d.address, id, d.driver),
            (None, true, _) => println!("0x{:02X}  claimed by a kernel driver, could be {:?}", d.address, d.candidates),
            (None, false, false) => println!("0x{:02X}  one of {:?}", d.address, d.candidates),
            (None, false, true) => println!("0x{:02X}  unknown device", d.address),
        }
    }

    // --- Flag addresses we deliberately did not probe ---
    // Some candidates (port expanders, DACs) treat a stray write as data, so the
    // identity probe is skipped unless discover(.., true) is used.
    let skipped = devices
        .iter()
        .filter(|d| d.probe_skipped_reason == Some(ProbeSkipReason::WriteSensitiveCandidate))
        .count();
    println!("{} address(es) not probed because a candidate is write-sensitive", skipped);
}
