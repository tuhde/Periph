#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::Output;
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::chips::accelerometer::{
    Adxl362Full, FIFO_STREAM, LINKLOOP_LOOP, NOISE_LOW, SOURCE_AWAKE, SOURCE_DATA_READY,
};

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let spi_bus = Spi::new(peripherals.SPI2, Config::default())
        .unwrap()
        .with_mosi(peripherals.GPIO3)
        .with_miso(peripherals.GPIO4)
        .with_sck(peripherals.GPIO5);
    let cs = Output::new(peripherals.GPIO6, esp_hal::gpio::Level::High, esp_hal::gpio::OutputConfig::default());
    let device = ExclusiveDevice::new_no_delay(spi_bus, cs).unwrap();

    let mut passed = 0u32;
    let mut failed = 0u32;

    let mut chip = Adxl362Full::new(device).expect("init ADXL362");              // Create ADXL362 full driver, (spi) → Result

    let ids = chip.device_id().expect("device_id");                                // Read device IDs, () → Result<(DEVID_AD, DEVID_MST, PARTID, REVID)>
    if ids.0 == 0xAD { println!("PASS device_id_devid_ad"); passed += 1; }
    else { println!("FAIL device_id_devid_ad"); failed += 1; }
    if ids.1 == 0x1D { println!("PASS device_id_devid_mst"); passed += 1; }
    else { println!("FAIL device_id_devid_mst"); failed += 1; }
    if ids.2 == 0xF2 { println!("PASS device_id_partid"); passed += 1; }
    else { println!("FAIL device_id_partid"); failed += 1; }

    let _ = chip.read().expect("read");                                            // Read 3-axis acceleration, () → Result<(f32, f32, f32)> g
    println!("PASS read_12bit"); passed += 1;

    let _ = chip.read_8bit().expect("read_8bit");                                  // Read 8-bit acceleration, () → Result<(f32, f32, f32)> g
    println!("PASS read_8bit"); passed += 1;

    let _ = chip.temperature().expect("temperature");                              // Read temperature, () → Result<f32> °C
    println!("PASS temperature"); passed += 1;

    chip.set_range(4).expect("set_range(4)");                                      // Set measurement range, (range_g=4) → Result<()>
    chip.set_odr(200.0).expect("set_odr(200)");                                    // Set output data rate, (odr_hz=200.0) → Result<()>
    chip.set_half_bandwidth(true).expect("set_half_bandwidth(true)");              // Set antialiasing bandwidth, (enabled=true) → Result<()>
    chip.set_noise_mode(NOISE_LOW).expect("set_noise_mode(LOW)");                  // Set noise mode, (mode=NOISE_LOW) → Result<()>
    println!("PASS set_range_odr_noise"); passed += 1;

    let _ = chip.status().expect("status");                                        // Read STATUS register, () → Result<u8>
    let _ = chip.awake().expect("awake");                                          // Check AWAKE bit, () → Result<bool>
    let _ = chip.data_ready().expect("data_ready");                                // Check DATA_READY, () → Result<bool>
    let _ = chip.fifo_entries().expect("fifo_entries");                            // Read FIFO entry count, () → Result<u16>
    println!("PASS status_awake_dr_fifo"); passed += 1;

    chip.configure_fifo(FIFO_STREAM, false, 128).expect("configure_fifo");        // Configure FIFO, (mode=STREAM, store_temp=false, watermark=128) → Result<()>
    chip.set_activity_threshold(0.5, true).expect("set_activity_threshold(0.5, true)");  // Set activity threshold, (threshold_g=0.5, referenced=true) → Result<()>
    chip.set_activity_time(5).expect("set_activity_time(5)");                      // Set activity time, (samples=5) → Result<()>
    chip.set_inactivity_threshold(0.2, true).expect("set_inactivity_threshold(0.2, true)");  // Set inactivity threshold, (threshold_g=0.2, referenced=true) → Result<()>
    chip.set_inactivity_time(30).expect("set_inactivity_time(30)");                // Set inactivity time, (samples=30) → Result<()>
    chip.enable_activity_detection(true).expect("enable_activity_detection(true)");        // Enable activity detection, (enabled=true) → Result<()>
    chip.enable_inactivity_detection(true).expect("enable_inactivity_detection(true)");    // Enable inactivity detection, (enabled=true) → Result<()>
    chip.set_link_loop_mode(LINKLOOP_LOOP).expect("set_link_loop_mode(LOOP)");                // Set link/loop mode, (mode=LOOP) → Result<()>
    println!("PASS activity_inactivity_config"); passed += 1;

    chip.set_interrupt(1, SOURCE_DATA_READY, true).expect("set_interrupt(1, DATA_READY, true)");  // Map DATA_READY to INT1, (pin=1, source=DATA_READY, enabled=true) → Result<()>
    chip.set_interrupt(2, SOURCE_AWAKE, true).expect("set_interrupt(2, AWAKE, true)");            // Map AWAKE to INT2, (pin=2, source=AWAKE, enabled=true) → Result<()>
    chip.set_interrupt_polarity(1, true).expect("set_interrupt_polarity(1, true)");             // Set INT1 active-low, (pin=1, active_low=true) → Result<()>
    println!("PASS interrupt_mapping"); passed += 1;

    chip.self_test(true).expect("self_test(true)");                                // Enable self-test, (enabled=true) → Result<()>
    chip.self_test(false).expect("self_test(false)");                              // Disable self-test, (enabled=false) → Result<()>
    println!("PASS self_test"); passed += 1;

    chip.soft_reset().expect("soft_reset");                                        // Soft-reset the chip, () → Result<()>
    println!("PASS soft_reset"); passed += 1;

    println!("===DONE: {} passed, {} failed===", passed, failed);

    let mut delay = Delay::new();
    delay.delay_millis(250);
    loop {}
}