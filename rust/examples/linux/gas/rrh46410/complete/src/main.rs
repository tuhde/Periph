//! Complete example for the RRH46410 — every API method of the Full driver.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::gas::rrh46410;
use periph::chips::gas::Rrh46410Full;

fn print_measurement(m: &rrh46410::Measurement) {
    if m.pbaq {
        println!("sample {}  TVOC {:.3} mg/m3  EtOH {:.3} ppm  {}", m.sample, m.tvoc_mg_m3, m.etoh_ppm,
                 if m.warmed_up { "ok" } else { "warming up" });
    } else {
        println!("sample {}  IAQ {:.1}  TVOC {:.2} mg/m3  EtOH {:.2} ppm  eCO2 {} ppm  rel {}  {}", m.sample, m.iaq,
                 m.tvoc_mg_m3, m.etoh_ppm, m.eco2_ppm, m.relative_iaq, if m.warmed_up { "ok" } else { "warming up" });
    }
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let bus = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create RRH46410 driver, (i2c, addr=0x38) → Rrh46410Full
    // Stores the bus only; no bus traffic yet. The module stretches the I²C clock.
    let mut chip = Rrh46410Full::new(bus, addr);
    // Check product ID, start IAQ 2nd Gen mode, (delay) → Result<()>
    // Retries Get Product ID for up to 1 s, verifies 0x2310, selects IAQ 2nd Gen (3 s samples).
    chip.init(&mut delay).expect("init");

    // Product ID and firmware, () → Result<(u16, [u8; 3])>
    // The product ID is always 0x2310.
    println!("{:?}", chip.product_id().expect("product id"));
    // ASIC tracking number, () → Result<[u8; 6]>
    // Unique per module.
    println!("{:02X?}", chip.tracking_number().expect("tracking number"));
    // Operation mode, () → Result<u8>
    // 1 = IAQ 2nd Gen after init.
    println!("mode {}", chip.get_mode().expect("mode"));

    // Ambient humidity, (percent %RH) → Result<()>
    // Sent as round(percent / 100 × 255); the module assumes 50 %RH otherwise.
    chip.set_humidity(45.0).expect("humidity");
    // Next sample, (delay) → Result<Measurement>
    // Waits for the sample counter to change; values are fixed during the warm-up.
    print_measurement(&chip.read(&mut delay).expect("read"));
    // Wait for a new sample, (delay, timeout_ms) → Result<bool>
    // True when the counter changed within the timeout.
    println!("new sample {}", chip.wait_for_sample(&mut delay, 4000).expect("wait"));
    // Latest result, () → Result<Option<Measurement>>
    // Does not wait; None before the first sample of the mode.
    if let Some(m) = chip.read_results().expect("results") {
        print_measurement(&m);
    }
    // Last decoded result, () → Measurement
    // The sample stored by read, read_results or wait_for_sample.
    print_measurement(&chip.last_result());
    // UBA rating, (iaq) → &'static str
    // "very good", "good", "medium", "poor" or "bad".
    println!("{}", rrh46410::air_quality_label(2.4));
    // Mode timing, (mode: Option<u8>) → f32 s / u8
    // 3 s and 100 samples in IAQ 2nd Gen mode.
    println!("period {} s warm-up {}", chip.sample_period(None), chip.warmup_samples(None));
    // Last status byte, () → Status
    // Error bits and the operation-mode error code (0xA = sensor damaged).
    println!("{:?}", chip.status());
    // New sample pending, () → Result<bool>
    // Compares the sample counter; with INT wired read the pin level instead.
    println!("pending {}", chip.poll_interrupt().expect("poll"));

    // Operation mode, (mode) → Result<()>
    // PBAQ: TVOC and ethanol only, every 5 s; restarts the warm-up.
    chip.set_mode(rrh46410::MODE_PBAQ).expect("pbaq");
    // Next sample, (delay) → Result<Measurement>
    // PBAQ results have a finer resolution (raw / 1000); IAQ and eCO2 are 0.
    print_measurement(&chip.read(&mut delay).expect("read"));
    // Operation mode, (mode) → Result<()>
    // ULP: one sample every 90 s at 0.23 mW.
    chip.set_mode(rrh46410::MODE_ULP).expect("ulp");
    // Mode timing, (mode: Option<u8>) → f32 s / u8
    // 90 s and 10 samples.
    println!("period {} s warm-up {}", chip.sample_period(None), chip.warmup_samples(None));

    // GPIO directions, (directions) → Result<()>
    // Bit 0 = GPIO1 output, bit 1 = GPIO2 output.
    chip.config_gpio(0x03).expect("config gpio");
    // Drive high, (mask) → Result<()>
    // GPIO1 high.
    chip.set_gpio(0x01).expect("set gpio");
    // GPIO levels, () → Result<u8>
    // Bits 0–3 = GPIO1–GPIO4.
    println!("gpio {:#06b}", chip.get_gpio().expect("get gpio"));
    // Drive low, (mask) → Result<()>
    // GPIO1 low.
    chip.clear_gpio(0x01).expect("clear gpio");

    let mut mode = [0u8; 1];
    // Raw request, (command, params, out) → Result<()>
    // Checksums added and verified; here Get Operation Mode.
    chip.send_command(0x10, &[], &mut mode).expect("raw");
    println!("raw mode {}", mode[0]);
    // One-time cleaning, (delay, confirm) → Result<()>
    // Runs for one minute, once in the sensor's lifetime; left disabled here.
    // chip.clean_sensor(&mut delay, true).expect("clean");
    // Suspend mode, () → Result<()>
    // Stops measuring, about 0.7 µA.
    chip.suspend().expect("suspend");
    // Reset, (delay) → Result<()>
    // Waits 500 ms and verifies the product ID; the module restarts in SUSPEND.
    chip.reset(&mut delay).expect("reset");
}
