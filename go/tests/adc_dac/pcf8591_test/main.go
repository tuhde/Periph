//go:build linux && !tinygo

// PCF8591 hardware test — Linux host.
//
// Drives every method in the PCF8591Full API and reports PASS/FAIL per
// check. Prints the standard ===DONE: ... === summary line at the end.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x48"), 0, 8)
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

	chip, err := adcdac.NewPCF8591Full(conn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}

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

	ch0, err := chip.ReadChannel(0)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_channel(0):", err)
	}
	check("read_channel(0) in [0, 255]", ch0 <= 255)

	ch3, err := chip.ReadChannel(3)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_channel(3):", err)
	}
	check("read_channel(3) in [0, 255]", ch3 <= 255)

	chOob, err := chip.ReadChannel(99)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_channel(99):", err)
	}
	check("read_channel(99) clamped", chOob <= 255)

	allRaw, err := chip.ReadAll()
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_all:", err)
	}
	check("read_all values in [0, 255]", allRaw[0] <= 255 && allRaw[1] <= 255 && allRaw[2] <= 255 && allRaw[3] <= 255)

	v0, err := chip.ReadChannelVoltage(0, 3.3, 0.0)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_channel_voltage:", err)
	}
	check("read_channel_voltage in [0, 3.3]", v0 >= 0.0 && v0 <= 3.3)

	vAll, err := chip.ReadAllVoltage(3.3, 0.0)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_all_voltage:", err)
	}
	check("read_all_voltage values in [0, 3.3]", vAll[0] >= 0.0 && vAll[0] <= 3.3 && vAll[3] >= 0.0 && vAll[3] <= 3.3)

	if err := chip.Configure(adcdac.PCF8591Mode4SingleEnded, false, false); err != nil {
		fmt.Fprintln(os.Stderr, "configure 4 SE:", err)
	}
	check("configure 4 single-ended accepted", true)

	if err := chip.Configure(adcdac.PCF8591Mode3Differential, false, false); err != nil {
		fmt.Fprintln(os.Stderr, "configure 3 diff:", err)
	}
	diff, err := chip.ReadDifferential(0)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read_differential:", err)
	}
	check("read_differential in [-128, 127]", diff >= -128 && diff <= 127)

	if err := chip.Configure(adcdac.PCF8591ModeMixed, false, false); err != nil {
		fmt.Fprintln(os.Stderr, "configure mixed:", err)
	}
	check("configure mixed mode accepted", true)

	if err := chip.Configure(adcdac.PCF8591Mode2Differential, false, false); err != nil {
		fmt.Fprintln(os.Stderr, "configure 2 diff:", err)
	}
	check("configure 2 differential accepted", true)

	if err := chip.Configure(adcdac.PCF8591Mode4SingleEnded, true, false); err != nil {
		fmt.Fprintln(os.Stderr, "configure auto-increment:", err)
	}
	if _, err := chip.ReadAll(); err != nil {
		fmt.Fprintln(os.Stderr, "read_all with auto-increment:", err)
	}
	check("read_all with auto-increment returns 4 values", true)

	if err := chip.Configure(adcdac.PCF8591Mode4SingleEnded, false, true); err != nil {
		fmt.Fprintln(os.Stderr, "configure enables DAC:", err)
	}
	check("configure enables DAC", true)

	if err := chip.SetDAC(0); err != nil {
		fmt.Fprintln(os.Stderr, "set_dac(0):", err)
	}
	check("set_dac(0) accepted", true)

	if err := chip.SetDAC(255); err != nil {
		fmt.Fprintln(os.Stderr, "set_dac(255):", err)
	}
	check("set_dac(255) accepted", true)

	if err := chip.SetDAC(128); err != nil {
		fmt.Fprintln(os.Stderr, "set_dac(128):", err)
	}
	check("set_dac(128) accepted", true)

	if err := chip.SetDACVoltage(0.0); err != nil {
		fmt.Fprintln(os.Stderr, "set_dac_voltage(0.0):", err)
	}
	check("set_dac_voltage(0.0) accepted", true)

	if err := chip.SetDACVoltage(1.0); err != nil {
		fmt.Fprintln(os.Stderr, "set_dac_voltage(1.0):", err)
	}
	check("set_dac_voltage(1.0) accepted", true)

	if err := chip.SetDACVoltage(0.5); err != nil {
		fmt.Fprintln(os.Stderr, "set_dac_voltage(0.5):", err)
	}
	check("set_dac_voltage(0.5) accepted", true)

	if err := chip.DisableDAC(); err != nil {
		fmt.Fprintln(os.Stderr, "disable_dac:", err)
	}
	check("disable_dac accepted", true)

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
