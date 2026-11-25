//go:build linux && !tinygo

// HY3131 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "SPI_BUS:", err)
		os.Exit(2)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "SPI_DEVICE:", err)
		os.Exit(2)
	}

	conn, err := connection.NewSPIConnection(bus, device, 0, 1_000_000, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	passed, failed := 0, 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}

	chip, err := adcdac.NewHY3131Minimal(conn, 1.2)
	if err != nil {
		fatal("new minimal:", err)
	}
	raw, err := chip.ReadRaw()
	check("ReadRaw returns int", err == nil)
	check("ReadRaw in 24-bit range", err == nil && raw >= -8388608 && raw <= 8388607)
	_, err = chip.ReadVoltage()
	check("ReadVoltage returns float", err == nil)
	_, err = chip.DataReady()
	check("DataReady returns bool", err == nil)

	f, err := adcdac.NewHY3131Full(conn, 1.2)
	if err != nil {
		fatal("new full:", err)
	}
	check("ConfigureAD1 accepted", f.ConfigureAD1(4, 2, 0, 0, 0, 0) == nil)
	_, err = f.ReadRaw()
	check("ReadRaw after ConfigureAD1", err == nil)
	check("SetAD1Input accepted", f.SetAD1Input(10, 3, 2) == nil)
	check("SetAD1Reference accepted", f.SetAD1Reference(1, 1) == nil)
	tcode, err := f.ReadTemperatureCode()
	check("ReadTemperatureCode returns int", err == nil)
	_, err = f.ReadTemperature(tcode)
	check("ReadTemperature returns float", err == nil)

	check("EnableAD2 accepted", f.EnableAD2(5, 1, 0, false, false, 0, false) == nil)
	ad2, err := f.ReadAD2Raw()
	check("ReadAD2Raw in 19-bit range", err == nil && ad2 >= -262144 && ad2 <= 262143)
	check("EnableLPF accepted", f.EnableLPF(0) == nil)
	_, err = f.ReadLPFRaw()
	check("ReadLPFRaw returns int", err == nil)
	check("EnableRMS accepted", f.EnableRMS() == nil)
	rms, err := f.ReadRMS()
	check("ReadRMS >= 0", err == nil && rms >= 0)
	check("EnablePeakHold accepted", f.EnablePeakHold(0) == nil)
	lo, hi, err := f.ReadPeak()
	check("ReadPeak min <= max", err == nil && lo <= hi)
	check("EnableInterrupts accepted", f.EnableInterrupts(0x04) == nil)
	_, err = f.ReadFlags()
	check("ReadFlags returns int", err == nil)
	check("Reset accepted", f.Reset() == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

func fatal(args ...interface{}) {
	fmt.Fprintln(os.Stderr, args...)
	os.Exit(2)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
