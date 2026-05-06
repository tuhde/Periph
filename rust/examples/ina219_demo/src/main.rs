use linux_embedded_hal::I2cdev;
use periph::chips::power::{Ina219Full, BRNG_32V, PGA_8, ADC_12BIT, MODE_SHUNT_BUS_CONT};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Ina219Full::new(dev, addr, 0.1, 2.0).expect("init INA219");

    chip.configure(BRNG_32V, PGA_8, ADC_12BIT, ADC_12BIT, MODE_SHUNT_BUS_CONT).unwrap();

    println!("V_bus       V_shunt     I          P");
    let mut v_sum = 0.0f32;
    let mut i_sum = 0.0f32;
    let mut p_sum = 0.0f32;
    let mut v_min = f32::MAX;
    let mut v_max = f32::MIN;
    let mut i_min = f32::MAX;
    let mut i_max = f32::MIN;
    let mut p_min = f32::MAX;
    let mut p_max = f32::MIN;

    std::thread::sleep(std::time::Duration::from_secs(1));

    for j in 0..10 {
        let v = chip.voltage().unwrap();
        let vs = chip.shunt_voltage().unwrap();
        let i = chip.current().unwrap();
        let p = chip.power().unwrap();

        v_sum += v;
        i_sum += i;
        p_sum += p;

        if v < v_min { v_min = v; }
        if v > v_max { v_max = v; }
        if i < i_min { i_min = i; }
        if i > i_max { i_max = i; }
        if p < p_min { p_min = p; }
        if p > p_max { p_max = p; }

        println!("{:.3}V   {:.5}V   {:.4}A   {:.4}W", v, vs, i, p);

        if j == 3 {
            println!(">>> Switch on your load now <<<");
        }

        std::thread::sleep(std::time::Duration::from_secs(1));
    }

    println!("min: {:.3}V  {:.4}A  {:.4}W", v_min, i_min, p_min);
    println!("max: {:.3}V  {:.4}A  {:.4}W", v_max, i_max, p_max);
    println!("mean: {:.3}V  {:.4}A  {:.4}W", v_sum / 10.0, i_sum / 10.0, p_sum / 10.0);
}