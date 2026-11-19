//go:build linux && !tinygo

// PCA9685 demo example — Linux host.
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"
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
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x40) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	// --- LED fader and servo sweep ---
	// Fade channel 0 at 1 kHz, show a phase-shifted pair, run a 16-channel chase,
	// switch to 50 Hz and sweep a servo on channel 15 from 1 ms to 2 ms, then show
	// all-channel writes, full ON/OFF, output modes, sleep/wake/restart with the
	// outputs preserved, the all-call and sub addresses, and finish with a reset.
	pwm, err := led.NewPca9685Full(conn) // Create PCA9685 driver, (connection) → (*Pca9685Full, error)
	must(err)
	must(pwm.SetFrequency(1000, 25e6)) // 1 kHz PWM for LEDs, (hz, oscHz) → error

	for i := 0; i <= 100; i++ {
		must(pwm.SetDuty(0, 0.5-0.5*math.Cos(math.Pi*float64(i)/50))) // Fade up and down, (channel, duty) → error
		time.Sleep(20 * time.Millisecond)
	}

	must(pwm.SetPwm(1, 0, 1024))    // 25 % duty, no delay, (channel, on, off) → error
	must(pwm.SetPwm(2, 2048, 3072)) // same duty, shifted by half a period
	on, off, err := pwm.GetPwm(2)   // Read back the counts, (channel) → (on, off, error)
	must(err)
	fmt.Println(on, off)
	time.Sleep(time.Second)

	for ch := uint8(0); ch < 16; ch++ {
		must(pwm.SetDuty(ch, 1)) // Chase across all channels, (channel, duty) → error
		time.Sleep(50 * time.Millisecond)
		must(pwm.SetDuty(ch, 0))
	}

	must(pwm.SetAllDuty(0.2)) // All channels together, (duty) → error
	time.Sleep(500 * time.Millisecond)
	must(pwm.SetAllPwm(0, 3072)) // 75 % on all channels, (on, off) → error
	time.Sleep(500 * time.Millisecond)
	must(pwm.SetAllDuty(0))
	must(pwm.SetFullOn(3)) // Constant high, (channel) → error
	time.Sleep(500 * time.Millisecond)
	must(pwm.SetFullOff(3)) // Constant low, (channel) → error

	must(pwm.SetFrequency(50, 25e6)) // Servo frequency, (hz, oscHz) → error
	for us := 1000; us <= 2000; us += 50 {
		must(pwm.SetPulseWidth(15, float64(us)/1e6)) // Servo pulse 1–2 ms, (channel, seconds) → error
		time.Sleep(50 * time.Millisecond)
	}
	hz, err := pwm.GetFrequency(25e6) // () → (float64 Hz, error)
	must(err)
	fmt.Println(hz)

	must(pwm.SetFrequency(1000, 25e6))
	must(pwm.SetDuty(4, 0.5))
	must(pwm.SetOutputMode(false, true, led.Pca9685OffLow)) // Inverted output, (openDrain, invert, offState) → error
	time.Sleep(500 * time.Millisecond)
	must(pwm.SetOutputMode(true, false, led.Pca9685OffHighZ)) // Open-drain, high-Z when OE = 1
	time.Sleep(500 * time.Millisecond)
	must(pwm.SetOutputMode(false, false, led.Pca9685OffLow)) // Back to totem-pole, not inverted
	must(pwm.SetUpdateOnAck(true))                           // Update on ACK, (enabled) → error
	must(pwm.SetUpdateOnAck(false))

	must(pwm.Sleep()) // Outputs pause, PWM registers kept, () → error
	time.Sleep(500 * time.Millisecond)
	must(pwm.Wake())                 // Outputs resume, () → error
	must(pwm.Restart())              // Restart sequence, () → error
	must(pwm.SetAllcall(true, 0x70)) // Respond to all-call, (enabled, address) → error
	must(pwm.SetAllcall(false, 0x70))
	must(pwm.SetSubaddress(1, 0x71, true)) // Respond to sub address 1, (index, address, enabled) → error
	must(pwm.SetSubaddress(1, 0x71, false))
	m1, m2, err := pwm.ReadMode() // () → (mode1, mode2, error)
	must(err)
	fmt.Println(m1, m2)
	must(pwm.Reset()) // Back to power-on state, () → error
	fmt.Println("===DONE: 0 passed, 0 failed===")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
