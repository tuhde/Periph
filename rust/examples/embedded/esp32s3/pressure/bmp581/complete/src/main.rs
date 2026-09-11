#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::pressure::{Bmp581Full, FIFO_BOTH, FIFO_STREAM, IIR_BYPASS, IIR_COEFF_3, BMP581_MODE_NORMAL, OSR_1X};

esp_app_desc!();

const ADDR: u8 = 0x46;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    let mut bmp = Bmp581Full::new(i2c, ADDR, false).expect("init BMP581");  // Create BMP581 driver, (i2c, addr=0x46)
    let cid = bmp.chip_id().expect("chip_id");                             // Read chip ID, () → u8
    println!("chip_id={:#x} (expect 0x50)", cid);

    bmp.configure(0x1C, OSR_1X, OSR_1X, true).expect("configure");         // Configure chip, (odr 0x00–0x1F, osr_p 0–7, osr_t 0–7, press_en bool) → Result<(), E>
    bmp.set_mode(BMP581_MODE_NORMAL).expect("set_mode");                   // Set power mode, (mode 0/1/2/3) → Result<(), E>
    bmp.set_iir_filter(IIR_COEFF_3, IIR_BYPASS).expect("set_iir");         // Set IIR filter, (coeff_p 0–7, coeff_t 0–7) → Result<(), E>
    bmp.configure_fifo(FIFO_BOTH, FIFO_STREAM, 8).expect("configure_fifo"); // Configure FIFO, (frame_sel 0–3, mode 0/1, threshold 0–31) → Result<(), E>
    let n = bmp.fifo_count().expect("fifo_count");                        // Read FIFO frame count, () → Result<u8, E>
    bmp.enable_drdy_interrupt(true).expect("enable_drdy");                // Enable data-ready interrupt, (enable bool) → Result<(), E>
    let drdy = bmp.data_ready().expect("data_ready");                     // Check data ready, () → Result<bool, E>
    let (p_f, t_f) = bmp.forced().expect("forced");                       // Trigger FORCED measurement, () → Result<(Pa, °C), E>
    let (p_pa, t_c) = bmp.both().expect("both");                          // Read both atomically, () → Result<(Pa, °C), E>
    let alt = bmp.altitude(101325.0).expect("altitude");                   // Compute altitude, (sea_level_pa=101325.0) → Result<f32, E>
    let st = bmp.status().expect("status");                               // Read STATUS, () → Result<u8, E>
    let ist = bmp.interrupt_status().expect("interrupt_status");          // Read INT_STATUS, () → Result<u8, E>
    let (op, ot) = bmp.effective_osr().expect("effective_osr");           // Read effective OSR, () → Result<(u8, u8), E>
    bmp.set_oor_threshold(110000.0, 200.0, 1).expect("set_oor");          // Set OOR threshold, (threshold_pa, range_pa, count_limit 0–3) → Result<(), E>
    bmp.software_reset().expect("software_reset");                        // Soft reset chip, () → Result<(), E>

    println!("P={:.1} Pa, T={:.2} C, alt={:.1} m, frames={}, drdy={}, st={:#x}, ist={:#x}, eff=({}, {}), t_f={:.2} p_f={:.1}",
        p_pa, t_c, alt, n, drdy, st, ist, op, ot, t_f, p_f);
    delay.delay_ms(1000);
    loop {}
}
