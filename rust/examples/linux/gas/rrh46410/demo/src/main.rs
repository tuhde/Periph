//! Demo for the RRH46410 — indoor air-quality monitor.

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
    let mut chip = Rrh46410Full::new(bus, addr);
    // Check product ID, start IAQ 2nd Gen mode, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    // --- Identity ---
    // Get Product ID returns 0x2310 and the firmware version; the tracking number
    // identifies the individual module.
    // Product ID and firmware, () → Result<(u16, [u8; 3])>
    let (pid, fw) = chip.product_id().expect("product id");
    println!("product {:#06X}, firmware {}.{}.{}", pid, fw[0], fw[1], fw[2]);
    // Tracking number, () → Result<[u8; 6]>; operation mode, () → Result<u8>
    println!("tracking {:02X?}, mode {}", chip.tracking_number().expect("tracking"), chip.get_mode().expect("mode"));

    // --- Humidity compensation ---
    // The MOx element reacts to humidity; feeding the real value improves the
    // results. The module assumes 50 %RH.
    // Ambient humidity, (percent %RH) → Result<()>
    chip.set_humidity(42.0).expect("humidity");

    // --- Indoor air-quality monitor ---
    // A new sample arrives every 3 s; the first 100 samples (about 5 min) are the
    // warm-up during which the values stay fixed.
    // Warm-up length and sample period, (mode: Option<u8>) → u8 / f32 s
    let warmup = chip.warmup_samples(None) as u32;
    println!("sample period {} s, warm-up {} samples", chip.sample_period(None), warmup);
    for _ in 0..20 {
        // Next sample, (delay) → Result<Measurement>
        if let Ok(m) = chip.read(&mut delay) {
            print_measurement(&m);
            let progress = if m.warmed_up { 100 } else { m.sample as u32 * 100 / warmup };
            // UBA rating, (iaq) → &'static str
            println!("  rating {}, warm-up {}%", rrh46410::air_quality_label(m.iaq), progress);
        }
    }

    // --- Interrupt instead of polling ---
    // poll_interrupt compares the sample counter without consuming the result; with
    // INT wired an ISR on its rising edge does the same job.
    for _ in 0..100 {
        // New sample pending, () → Result<bool>
        if chip.poll_interrupt().unwrap_or(false) {
            break;
        }
        sleep_ms!(100);
    }
    // Wait for a new sample, (delay, timeout_ms) → Result<bool>; latest result, () → Result<Option<Measurement>>
    if chip.wait_for_sample(&mut delay, 4000).unwrap_or(false) || chip.read_results().is_ok() {
        print!("latest ");
        // Last decoded result, () → Measurement
        print_measurement(&chip.last_result());
    }

    // --- Other operation modes ---
    // PBAQ reports TVOC and ethanol for public buildings; ULP saves power with
    // one sample every 90 s. Each mode change restarts the warm-up.
    for (mode, name) in [(rrh46410::MODE_PBAQ, "PBAQ"), (rrh46410::MODE_ULP, "ULP")] {
        // Operation mode, (mode) → Result<()>
        chip.set_mode(mode).expect("mode");
        // Mode timing, (mode: Option<u8>) → f32 s / u8
        println!("{}: sample every {} s, warm-up {} samples", name, chip.sample_period(None), chip.warmup_samples(None));
    }
    // Operation mode, (mode) → Result<()>
    chip.set_mode(rrh46410::MODE_PBAQ).expect("pbaq");
    // Next sample, (delay) → Result<Measurement>
    print_measurement(&chip.read(&mut delay).expect("read"));

    // --- GPIOs ---
    // GPIO1 and GPIO2 can drive an indicator LED or a fan relay.
    // Both outputs, (directions) → Result<()>
    chip.config_gpio(0x03).expect("gpio");
    for _ in 0..3 {
        // GPIO1 high, (mask) → Result<()>
        chip.set_gpio(0x01).expect("set");
        sleep_ms!(200);
        // GPIO1 low, (mask) → Result<()>
        chip.clear_gpio(0x01).expect("clear");
        sleep_ms!(200);
    }
    // GPIO levels, () → Result<u8>; status, () → Status
    println!("GPIO levels {:#06b}, status {:?}", chip.get_gpio().expect("gpio"), chip.status());
    let mut mode = [0u8; 1];
    // Raw request, (command, params, out) → Result<()>
    chip.send_command(0x10, &[], &mut mode).expect("raw");
    println!("raw mode {}", mode[0]);

    // --- Wrap up ---
    // clean_sensor(delay, true) runs the 1-minute cleaning once in the sensor's
    // lifetime; it is skipped here. Suspend stops measuring; reset restarts the
    // module in SUSPEND.
    // Suspend mode, () → Result<()>
    chip.suspend().expect("suspend");
    // Reset, (delay) → Result<()>
    chip.reset(&mut delay).expect("reset");
    // Operation mode, () → Result<u8>
    println!("mode after reset {}", chip.get_mode().expect("mode"));
}
