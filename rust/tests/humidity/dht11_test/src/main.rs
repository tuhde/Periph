use linux_embedded_hal::I2cdev;
use periph::chips::humidity::Dht11Full;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}", $label);
            $failed += 1;
        }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut dht = Dht11Full::new(dev, addr);

    let mut passed = 0i32;
    let mut failed = 0i32;

    match dht.read_raw() {
        Ok(raw) => {
            let sum = (raw[0] as u16 + raw[1] as u16 + raw[2] as u16 + raw[3] as u16) & 0xFF;
            check_true!(sum == raw[4] as u16, "checksum", passed, failed);
        }
        Err(e) => {
            println!("FAIL checksum: {:?}", e);
            failed += 1;
        }
    }

    match dht.read() {
        Ok((temp, hum)) => {
            check_true!(temp > -40.0 && temp < 80.0, "temperature_range", passed, failed);
            check_true!(hum >= 0.0 && hum <= 100.0, "humidity_range", passed, failed);
        }
        Err(e) => {
            println!("FAIL read: {:?}", e);
            failed += 1;
        }
    }

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
