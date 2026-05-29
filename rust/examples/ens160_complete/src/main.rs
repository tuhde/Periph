use linux_embedded_hal::I2cdev;
use periph::chips::gas::Ens160Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x52);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = Ens160Full::new(dev, addr).expect("init ENS160"); // Create ENS160 driver, (i2c, addr=0x52)

    let (major, minor, release) = sensor.get_firmware_version().expect("get fw");  // Get firmware version, () → (u8, u8, u8)
                                                                                     // switches to IDLE, issues GET_APPVER, returns to STANDARD
    println!("Firmware: {}.{}.{}", major, minor, release);

    sensor.set_compensation(25.0, 50.0).expect("set comp");  // Set compensation, (temp_celsius, rh_percent) → ()
                                                              // improves accuracy with external T/RH readings

    sensor.configure_interrupt(true, false, false, true, false).expect("config int");  // Configure interrupt, (enabled, active_high, push_pull, on_data, on_gpr) → ()
                                                                                       // sets INTn pin behavior for new data notification

    println!("Waiting for warm-up...");
    loop {                                                        // Wait for valid data, () → blocks until warm
        let status = sensor.wait_for_new_data(2000).unwrap();
        if (status >> 2) & 0x03 == 0 { break; }
    }

    let tvoc = sensor.read_tvoc().expect("read tvoc");       // Read TVOC, () → f32 ppb
    let eco2 = sensor.read_eco2().expect("read eco2");       // Read eCO2, () → f32 ppm
    let aqi = sensor.read_aqi().expect("read aqi");          // Read AQI, () → u8 1–5
    let ethanol = sensor.read_ethanol().expect("read ethanol");  // Read ethanol, () → f32 ppb
                                                              // alias of DATA_TVOC at 0x22
    let r1 = sensor.read_raw_resistance(1).expect("read r1");  // Read raw resistance, (sensor=1 or 4) → f32 Ohms
    let r4 = sensor.read_raw_resistance(4).expect("read r4");  // Read raw resistance, (sensor=1 or 4) → f32 Ohms
    let (temp_actual, rh_actual) = sensor.read_compensation_actuals().expect("read actuals");  // Read compensation actuals, () → (f32, f32)
                                                              // returns T/RH values used by sensor

    println!("TVOC={} ppb, eCO2={} ppm, AQI={}", tvoc, eco2, aqi);
    println!("Ethanol={} ppb, R1={} Ohm, R4={} Ohm", ethanol, r1, r4);
    println!("Actual T={:.1} C, RH={:.1} %", temp_actual, rh_actual);

    sensor.sleep().expect("sleep");                          // Enter deep sleep, () → ()
                                                              // reduces current to ~10 uA
    std::thread::sleep(std::time::Duration::from_secs(1));
    sensor.wake().expect("wake");                            // Wake and resume sensing, () → ()
                                                              // transitions IDLE then STANDARD
}
