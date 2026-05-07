use linux_embedded_hal::I2cdev;
use periph::chips::power::Ina219Full;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ina219Full::new(dev, addr, 0.1, 2.0).expect("init INA219");

    // poll once per second for 10 seconds to characterize a power rail
    println!("V          A          W");
    let mut v_min = 0.0f32;
    let mut v_max = 0.0f32;
    let mut v_sum = 0.0f32;
    let mut i_min = 0.0f32;
    let mut i_max = 0.0f32;
    let mut i_sum = 0.0f32;
    let mut p_min = 0.0f32;
    let mut p_max = 0.0f32;
    let mut p_sum = 0.0f32;

    for i in 0..10 {
        // switch on the load at sample 5 to see the step in current and power

        while !chip.conversion_ready().unwrap() {
            sleep(Duration::from_millis(10));
        }

        let v = chip.voltage().unwrap();
        let c = chip.current().unwrap();
        let p = chip.power().unwrap();
        println!("V={:.3}V  I={:.4}A  P={:.4}W", v, c, p);

        if i == 0 {
            v_min = v;
            v_max = v;
            i_min = c;
            i_max = c;
            p_min = p;
            p_max = p;
        } else {
            v_min = v_min.min(v);
            v_max = v_max.max(v);
            i_min = i_min.min(c);
            i_max = i_max.max(c);
            p_min = p_min.min(p);
            p_max = p_max.max(p);
        }
        v_sum += v;
        i_sum += c;
        p_sum += p;

        sleep(Duration::from_secs(1));
    }

    println!("V: min={:.3} max={:.3} mean={:.3}", v_min, v_max, v_sum / 10.0);
    println!("I: min={:.4} max={:.4} mean={:.4}", i_min, i_max, i_sum / 10.0);
    println!("P: min={:.4} max={:.4} mean={:.4}", p_min, p_max, p_sum / 10.0);
}
