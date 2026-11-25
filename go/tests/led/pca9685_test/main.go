//go:build linux && !tinygo

// PCA9685 hardware test — Linux host.
//
// Talks to /dev/i2c-N and runs the PCA9685 check sequence. Prints PASS/FAIL
// per check and ends with the standard ===DONE: ... === line. Exits 0 on full
// pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x40"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	pwm, err := led.NewPca9685Minimal(conn)
	if err != nil {
		check("init", false)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		os.Exit(1)
	}
	check("constants", led.Pca9685Channels == 16)
	check("set_duty", pwm.SetDuty(0, 0.5) == nil && pwm.SetDuty(15, 0) == nil && pwm.SetDuty(1, 1) == nil)
	check("set_duty_range", pwm.SetDuty(16, 0.5) != nil && pwm.SetDuty(0, 1.5) != nil)

	full, err := led.NewPca9685Full(conn)
	if err != nil {
		check("init_full", false)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		os.Exit(1)
	}
	check("set_pwm", full.SetPwm(2, 409, 1228) == nil && full.SetPwm(2, 5, 5) != nil)
	on, off, e1 := full.GetPwm(2)
	check("get_pwm", e1 == nil && on == 409 && off == 1228)
	check("full_on_off", full.SetFullOn(3) == nil && full.SetFullOff(3) == nil)
	check("all", full.SetAllDuty(0.1) == nil && full.SetAllPwm(0, 2048) == nil && full.SetAllDuty(0) == nil)
	check("set_frequency", full.SetFrequency(50, 25e6) == nil)
	hz, e2 := full.GetFrequency(25e6)
	check("get_frequency", e2 == nil && hz > 49.9 && hz < 50.1)
	check("pulse_width", full.SetPulseWidth(15, 0.0015) == nil)
	check("sleep_wake", full.Sleep() == nil && full.Wake() == nil && full.Restart() == nil)
	check("output_mode", full.SetOutputMode(false, false, led.Pca9685OffLow) == nil && full.SetUpdateOnAck(false) == nil)
	check("allcall", full.SetAllcall(false, 0x70) == nil)
	check("subaddress", full.SetSubaddress(1, 0x71, false) == nil)
	m1, m2, e3 := full.ReadMode()
	check("read_mode", e3 == nil && m1&0x10 == 0 && m2&0x04 == 0x04)
	check("reset", full.Reset() == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
