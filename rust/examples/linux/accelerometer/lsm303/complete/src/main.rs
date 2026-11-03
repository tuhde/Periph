use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::{
    Lsm303Full, MODE_HIGH_RESOLUTION, FIFO_STREAM, MAG_MODE_CONTINUOUS, SOURCE_CLICK,
};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Lsm303Full::new(dev).expect("init LSM303");

    // --- Accelerometer readings ---
    let (ax, ay, az) = chip.read_acceleration().expect("read acceleration");
    println!("accel x={:.4} y={:.4} z={:.4} g", ax, ay, az);

    let (rx, ry, rz) = chip.read_acceleration_raw().expect("read raw acceleration");
    println!("raw accel {} {} {}", rx, ry, rz);

    println!("status 0x{:02X}", chip.read_status().expect("read status"));
    println!("new data {}", chip.new_data_available().expect("new data available"));

    // --- Accelerometer configuration ---
    chip.set_range(4).expect("set range");
    chip.set_data_rate(50).expect("set data rate");
    chip.set_mode(MODE_HIGH_RESOLUTION).expect("set mode");
    chip.set_axes(true, true, true).expect("set axes");

    // --- High-pass filter ---
    chip.set_high_pass(0, 0, false, true, false, false).expect("set high pass");
    println!("reference {}", chip.reset_high_pass().expect("reset high pass"));
    chip.set_reference(0).expect("set reference");

    // --- FIFO ---
    chip.set_fifo(FIFO_STREAM, 16, false).expect("set fifo");
    println!("fifo level {}", chip.fifo_level().expect("fifo level"));
    let (wm, ov, empty, level) = chip.read_fifo_status().expect("read fifo status");
    println!("fifo wm={} ov={} empty={} level={}", wm, ov, empty, level);
    let mut samples = [(0.0f32, 0.0f32, 0.0f32); 8];
    let n = chip.read_fifo(8, &mut samples).expect("read fifo");
    println!("fifo samples {}", n);

    // --- Inertial interrupt generators ---
    chip.set_motion_interrupt(1, 0x02, 0.5, 10.0, false).expect("set motion interrupt");
    chip.set_free_fall(1, 0.35, 30.0).expect("set free fall");
    chip.set_orientation(1, false, false).expect("set orientation");
    chip.set_latch(1, true).expect("set latch");
    println!("int1 source 0x{:02X}", chip.read_interrupt_source(1).expect("read interrupt source"));

    // --- Click detection ---
    chip.set_click(0, 0x04, 0.8, 20.0, 40.0, 200.0).expect("set click");
    println!("click source 0x{:02X}", chip.read_click_source().expect("read click source"));

    // --- Interrupt routing ---
    chip.set_interrupt_polarity(false).expect("set interrupt polarity");
    chip.enable_interrupt(SOURCE_CLICK, 1).expect("enable interrupt");
    chip.disable_interrupt(SOURCE_CLICK, 1).expect("disable interrupt");

    // --- Magnetometer ---
    chip.set_mag_range(250).expect("set mag range");
    chip.set_mag_data_rate(30.0).expect("set mag data rate");
    chip.set_mag_mode(MAG_MODE_CONTINUOUS).expect("set mag mode");
    let (mx, my, mz) = chip.read_magnetic_raw().expect("read raw magnetic");
    println!("raw mag {} {} {}", mx, my, mz);
    let (drdy, lock) = chip.read_mag_status().expect("read mag status");
    println!("mag drdy={} lock={}", drdy, lock);
    let identity = chip.read_mag_identity().expect("read mag identity");
    println!("identity 0x{:02X}{:02X}{:02X}", identity[0], identity[1], identity[2]);
    chip.enable_temperature(true).expect("enable temperature");
    println!("temperature {:.2} C", chip.read_temperature_delta().expect("read temperature"));

    // --- Compass heading ---
    println!("heading {:.1} deg", chip.heading(true).expect("heading"));
    println!("flat heading {:.1} deg", chip.heading(false).expect("flat heading"));

    // --- Miscellaneous ---
    chip.reboot().expect("reboot");
}
