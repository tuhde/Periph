//go:build linux && !tinygo

// RRH46410 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the RRH46410 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gas"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)
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
	if failed > 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
