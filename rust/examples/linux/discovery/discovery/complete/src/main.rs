use linux_embedded_hal::I2cdev;
use periph::discovery::{discover, scan, scan_detailed, FIRST_ADDRESS, LAST_ADDRESS};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let mut dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    let addresses = scan(&mut dev).expect("scan");                                    // Scan bus, (i2c) → Vec<u8> 7-bit addresses
                                                                                      // zero-length write per address, read byte on 0x30-0x37 / 0x50-0x5F; EBUSY counts as present
    println!("{:02X?}", addresses);

    let detail = scan_detailed(&mut dev, FIRST_ADDRESS, LAST_ADDRESS).expect("scan"); // Scan with kernel-binding info, (i2c, first=0x08, last=0x77) → BTreeMap<u8, bool>
                                                                                      // value is true when a kernel driver owns the address (i2cdetect "UU")
    println!("{:02X?}", detail);

    for d in discover(&mut dev, false).expect("discover") {                           // Discover chips, (i2c, active=false) → Vec<DiscoveredDevice>
                                                                                      // identity reads confirm a chip only when exactly one candidate matches
        println!("{:#?}", d);
    }

    let all = discover(&mut dev, true).expect("discover");                            // Discover chips incl. write-sensitive addresses, (i2c, active=true) → Vec<DiscoveredDevice>
                                                                                      // also probes addresses shared with PCF8574/PCF8591/MCP4725-style chips; may change their outputs
    println!("{}", all.len());
}
