#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::gyroscope::{
    L3g4200dFull, FS_500_DPS, FS_2000_DPS, ODR_200_HZ, FIFO_STREAM,
};

esp_app_desc!();

const ADDR: u8 = 0x68;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut gyro = L3g4200dFull::new(i2c, ADDR, false).expect("init L3G4200D");      // Create L3G4200D driver, (i2c, ADDR=0x68)
    let cid = gyro.who_am_i().expect("who_am_i");                                     // Read WHO_AM_I, () → u8
    gyro.configure(ODR_200_HZ, 0, FS_500_DPS).expect("configure");                     // Configure chip, (odr, bandwidth, full_scale) → ()
    gyro.enable_axes(true, true, true).expect("enable_axes");                          // Enable axes, (x, y, z) → ()
    gyro.set_full_scale(2000).expect("set_full_scale");                                // Set full scale, (full_scale 250/500/2000) → ()
    let ready = gyro.data_ready().expect("data_ready");                                // Check data ready, () → bool
    let status = gyro.status().expect("status");                                       // Read STATUS, () → u8
    let temp = gyro.temperature().expect("temperature");                                // Read temperature, () → i8
    gyro.enable_highpass(0, 4).expect("enable_highpass");                             // Enable high-pass, (mode 0–3, cutoff 0–9) → ()
    gyro.disable_highpass().expect("disable_highpass");                                // Disable high-pass, () → ()
    gyro.set_interrupt(true, false, true, false, true, false, false, true).expect("set_interrupt");  // Configure INT1, (...) → ()
    gyro.set_threshold('x', 87.5).expect("set_threshold");                            // Set X threshold, (axis, threshold_dps) → ()
    gyro.set_duration(4, false).expect("set_duration");                                // Set INT1 duration, (samples 0–127, wait=false) → ()
    gyro.set_data_ready_pin(true).expect("set_data_ready_pin");                        // Route DRDY to INT2, (enable=true) → ()
    gyro.enable_fifo(FIFO_STREAM, 10).expect("enable_fifo");                          // Enable FIFO, (mode 0–4, watermark=0) → ()
    gyro.disable_fifo().expect("disable_fifo");                                        // Disable FIFO, () → ()
    let samples = gyro.fifo_samples().expect("fifo_samples");                          // Read FIFO count, () → u8
    gyro.power_down().expect("power_down");                                            // Enter power-down, () → ()
    gyro.wake_up().expect("wake_up");                                                  // Wake from power-down, () → ()
    gyro.sleep().expect("sleep");                                                      // Enter sleep mode, () → ()
    let int_src = gyro.read_int_source().expect("read_int_source");                    // Read & clear INT1_SRC, () → u8
    let (x, y, z) = gyro.angular_rate().expect("angular_rate");                        // Read X/Y/Z angular rate, () → (f32, f32, f32) rad/s
    println!("X={:.2} Y={:.2} Z={:.2} rad/s, T={}, ready={}, status=0x{:02X}, fifo={}, src=0x{:02X}, cid=0x{:02X}",
             x, y, z, temp, ready, status, samples, int_src, cid);
    let _ = FS_2000_DPS;
    loop { delay.delay_ms(1000); }
}
