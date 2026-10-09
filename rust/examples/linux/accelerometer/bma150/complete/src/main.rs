use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::Bma150Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Bma150Full::new(dev, addr).expect("init BMA150");

    chip.set_range(8).expect("set_range");          // Select ±8 g
    chip.set_bandwidth(190).expect("set_bandwidth"); // 190 Hz
    let raw = chip.read_raw().expect("read_raw");
    let temp = chip.read_temperature().expect("read_temperature");
    let _ready = chip.new_data_available().expect("new_data_available");
    chip.set_shadow(false).expect("set_shadow");
    chip.set_low_g(0.4, 40, 0.0, 0).expect("set_low_g");
    chip.set_high_g(4.0, 2, 0.0, 0).expect("set_high_g");
    chip.set_any_motion(0.5, 3).expect("set_any_motion");
    chip.set_alert(false).expect("set_alert");
    chip.set_latch(true).expect("set_latch");
    let status = chip.poll_interrupt().expect("poll_interrupt");
    chip.clear_interrupt().expect("clear_interrupt");
    chip.set_wake_up(true, 80).expect("set_wake_up");
    let xyz = chip.read().expect("read");
    let (al, ml) = chip.read_version().expect("read_version");
    let c1 = chip.read_customer(0).expect("read_customer");
    chip.write_customer(0, 0xA5).expect("write_customer");
    let st = chip.self_test().expect("self_test");
    chip.soft_reset().expect("soft_reset");
    chip.sleep().expect("sleep");
    chip.wake().expect("wake");
    println!("raw=({},{},{}) temp={:.1} status=0x{:02X} al={} ml={} c1=0x{:02X} st={}",
             raw.0, raw.1, raw.2, temp, status, al, ml, c1, if st { "PASS" } else { "FAIL" });
    println!("xyz=({:.3}, {:.3}, {:.3})", xyz.0, xyz.1, xyz.2);
}
