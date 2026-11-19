//go:build tinygo

// PCA9685 complete example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.I2C1 on the Pico W with GP4 = SDA and GP5 = SCL.
package main

import (
	"fmt"
	"machine"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

var _ = fmt.Sprint
var _ = math.Pi

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	i2c := machine.I2C1
	must(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x40, nil, nil) // Create I2C connection, (i2c, addr=0x40) → (*I2CConnection)

	pwm, err := led.NewPca9685Full(conn) // Create PCA9685 driver, (connection) → (*Pca9685Full, error)
	must(err)
	must(pwm.SetDuty(0, 0.25))     // Set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → error
	must(pwm.SetPwm(1, 409, 1228)) // Phase-shifted PWM, (channel, on 0–4095, off 0–4095) → error
	// 10 % delay, 20 % duty
	on, off, err := pwm.GetPwm(1) // Read the ON/OFF counts, (channel) → (on, off uint16, error)
	must(err)
	fmt.Println(on, off)
	must(pwm.SetFullOn(2))       // Hold a channel high, (channel) → error
	must(pwm.SetFullOff(2))      // Hold a channel low, (channel) → error
	must(pwm.SetAllDuty(0.1))    // Same duty on all 16 channels, (duty 0.0–1.0) → error
	must(pwm.SetAllPwm(0, 2048)) // Same ON/OFF counts on all channels, (on, off) → error
	must(pwm.SetAllDuty(0))
	must(pwm.SetFrequency(50, 25e6))  // PWM frequency, (hz 24–1526, oscHz) → error
	hz, err := pwm.GetFrequency(25e6) // Programmed PWM frequency, (oscHz) → (float64 Hz, error)
	must(err)
	fmt.Println(hz)
	must(pwm.SetPulseWidth(15, 0.0015)) // Servo pulse width, (channel, seconds) → error
	// 1.5 ms at 50 Hz = servo centre
	time.Sleep(time.Second)
	must(pwm.Sleep())                                        // Low-power mode, outputs stop, () → error
	must(pwm.Wake())                                         // Wake and restart running channels, () → error
	must(pwm.Restart())                                      // Datasheet restart sequence, () → error
	must(pwm.SetOutputMode(false, false, led.Pca9685OffLow)) // MODE2 output driver, (openDrain, invert, offState) → error
	must(pwm.SetUpdateOnAck(false))                          // Outputs change on STOP (false) or ACK (true), (enabled) → error
	must(pwm.SetAllcall(false, 0x70))                        // LED All Call address, (enabled, address) → error
	must(pwm.SetSubaddress(1, 0x71, false))                  // LED Sub Call address, (index 1–3, address, enabled) → error
	m1, m2, err := pwm.ReadMode()                            // Raw MODE1/MODE2, () → (mode1, mode2, error)
	must(err)
	fmt.Println(m1, m2)
	// must(pwm.UseExternalClock()) // Sticky EXTCLK switch, () → error (needs a clock on EXTCLK)
	// must(led.Pca9685SoftwareReset(generalCall)) // Reset every PCA9685 on the bus, (Connection at address 0x00) → error
	must(pwm.Reset()) // Write power-on defaults, () → error
	fmt.Println("===DONE: 0 passed, 0 failed===")
}
