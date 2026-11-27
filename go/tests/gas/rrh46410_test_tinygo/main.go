//go:build tinygo

// RRH46410 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a RRH46410 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the
// standard ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gas"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	conn := connection.NewI2CConnection(i2c, 0x38, nil, nil)
	chip, _ := gas.NewRRH46410Full(conn)

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	check("init", chip.Init() == nil)
	pid, _, err := chip.ProductID()
	check("product_id", err == nil && pid == 0x2310)
	tn, err := chip.TrackingNumber()
	check("tracking_number", err == nil && len(tn) == 6)
	mode, err := chip.GetMode()
	check("mode_iaq", err == nil && mode == gas.RRH46410ModeIAQ2ndGen)
	check("set_humidity", chip.SetHumidity(50) == nil && chip.Status().Raw == 0)
	r, err := chip.Read()
	check("read", err == nil)
	check("read_iaq_range", r.IAQ >= 0 && r.IAQ <= 25.5)
	check("read_not_warmed_up", !r.WarmedUp)
	ok, err := chip.WaitForSample(5 * time.Second)
	check("wait_for_sample", err == nil && ok)
	_, ok, err = chip.ReadResults()
	check("read_results", err == nil && ok)
	ok, err = chip.PollInterrupt()
	check("poll_interrupt_false", err == nil && !ok)
	hits := make(chan gas.RRH46410Result, 8)
	check("on_interrupt_subscribe", chip.OnInterrupt(func(r gas.RRH46410Result) { hits <- r }) == nil)
	time.Sleep(7 * time.Second)
	check("off_interrupt", chip.OffInterrupt() == nil)
	check("on_interrupt", len(hits) >= 1)
	check("mode_pbaq", chip.SetMode(gas.RRH46410ModePBAQ) == nil)
	r, err = chip.Read()
	check("read_pbaq", err == nil && r.PBAQ)
	check("config_gpio", chip.ConfigGPIO(0x03) == nil)
	check("set_gpio", chip.SetGPIO(0x01) == nil)
	g, err := chip.GetGPIO()
	check("get_gpio_high", err == nil && g&1 == 1)
	check("clear_gpio", chip.ClearGPIO(0x01) == nil)
	g, err = chip.GetGPIO()
	check("get_gpio_low", err == nil && g&1 == 0)
	raw, err := chip.SendCommand(0x10, nil, 1)
	check("send_command", err == nil && raw[0] == gas.RRH46410ModePBAQ)
	check("suspend", chip.Suspend() == nil)
	mode, err = chip.GetMode()
	check("mode_suspend", err == nil && mode == gas.RRH46410ModeSuspend)
	check("reset", chip.Reset() == nil)
	mode, err = chip.GetMode()
	check("mode_after_reset", err == nil && mode == gas.RRH46410ModeSuspend)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
