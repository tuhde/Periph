use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::Apds9960Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x39);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Apds9960Full::new(dev, addr, &mut delay).expect("init APDS9960");

    println!("chip_id: 0x{:02X}", chip.chip_id().unwrap());

    let (c, r, g, b) = chip.color().unwrap();
    println!("C={} R={} G={} B={}", c, r, g, b);
    println!("clear: {}", chip.color_clear().unwrap());
    println!("red: {}", chip.color_red().unwrap());
    println!("green: {}", chip.color_green().unwrap());
    println!("blue: {}", chip.color_blue().unwrap());

    chip.configure_als(0xB6, 1).unwrap();
    chip.configure_wait(0xFF, false).unwrap();
    chip.enable_wait(true).unwrap();

    chip.enable_proximity(true).unwrap();
    chip.configure_proximity_led(0, 0, 0, 1).unwrap();
    chip.set_led_boost(0).unwrap();
    println!("proximity: {}", chip.proximity().unwrap());

    chip.als_threshold(100, 60000).unwrap();
    chip.proximity_threshold(10, 200).unwrap();
    chip.set_persistence(0, 1).unwrap();

    chip.enable_als_interrupt(true).unwrap();
    chip.enable_proximity_interrupt(true).unwrap();
    chip.clear_als_interrupt().unwrap();
    chip.clear_proximity_interrupt().unwrap();
    chip.clear_all_interrupts().unwrap();

    chip.set_proximity_offset(10, -5).unwrap();
    chip.set_proximity_mask(false, false, false, false).unwrap();

    chip.enable_gesture(true).unwrap();
    chip.configure_gesture(1, 0, 0, 1, 1, 50, 20).unwrap();
    println!("gesture_available: {}", chip.gesture_available().unwrap());
    println!("gesture_fifo_level: {}", chip.gesture_fifo_level().unwrap());
    let mut fifo = [(0u8, 0u8, 0u8, 0u8); 32];
    let n = chip.read_gesture_fifo(&mut fifo).unwrap();
    println!("gesture_fifo read: {} datasets", n);
    chip.clear_gesture_fifo().unwrap();
    chip.enable_gesture_interrupt(false).unwrap();
    chip.enable_gesture(false).unwrap();

    println!("status: 0x{:02X}", chip.status().unwrap());
    println!("is_als_valid: {}", chip.is_als_valid().unwrap());
    println!("is_proximity_valid: {}", chip.is_proximity_valid().unwrap());
    println!("is_als_saturated: {}", chip.is_als_saturated().unwrap());
    println!("is_proximity_saturated: {}", chip.is_proximity_saturated().unwrap());

    chip.enable_proximity(false).unwrap();
}
