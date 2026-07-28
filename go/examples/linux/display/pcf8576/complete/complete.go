//go:build linux && !tinygo

// PCF8576 complete example — Linux host.
//
// Exercises every method in the PCF8576Full API: clear, device_select,
// set_mode, set_blink, set_bank, write_raw, enable, and disable.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/display"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x38) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	lcd, err := display.NewPCF8576Full(conn) // Create PCF8576 driver, (connection) → (*PCF8576Full, error)
	if err != nil {
		panic(err)
	}

	if err := lcd.Clear(); err != nil { // Blank the display, () → error
		panic(err)
	}
	// zeros all 40 columns of display RAM

	if err := lcd.DeviceSelect(0); err != nil { // Select device on the bus, (subaddress 0–7) → error
		panic(err)
	}
	// sets the subaddress counter for cascaded use

	if err := lcd.SetMode(display.PCF8576Backplanes4, display.PCF8576Bias1_3); err != nil { // Set drive mode, (backplanes 1–4, bias 0/1) → error
		panic(err)
	}
	// configures 1:4 multiplex with 1/3 bias

	if err := lcd.SetBlink(display.PCF8576Blink2Hz, false); err != nil { // Set blink frequency, (frequency 0–3, alternate_bank=false) → error
		panic(err)
	}
	// ~2 Hz blink for visual attention

	if err := lcd.SetBank(display.PCF8576Bank0, display.PCF8576Bank0); err != nil { // Select RAM bank, (input_bank 0/1, output_bank 0/1) → error
		panic(err)
	}
	// selects rows 0-1 for both input and output

	digits := [4]uint8{5, 6, 7, 8}
	out := [4]uint8{
		display.PCF8576SevenSeg[digits[0]], // Encode 7-segment digit, (digit 0–9) → uint8
		display.PCF8576SevenSeg[digits[1]], // Encode 7-segment digit, (digit 0–9) → uint8
		display.PCF8576SevenSeg[digits[2]], // Encode 7-segment digit, (digit 0–9) → uint8
		display.PCF8576SevenSeg[digits[3]], // Encode 7-segment digit, (digit 0–9) → uint8
	}
	if err := lcd.WriteRaw(0, out[:]); err != nil { // Write raw bytes, (address 0–39, data) → error
		panic(err)
	}
	// sets data pointer to 0 and writes all four digits

	if err := lcd.Disable(); err != nil { // Disable display output, () → error
		panic(err)
	}
	// blanks the panel while keeping RAM contents

	if err := lcd.Enable(); err != nil { // Enable display output, () → error
		panic(err)
	}
	// resumes output from RAM with the prior configuration

	_ = display.PCF8576Backplanes2
	_ = display.PCF8576Bias1_2
	_ = display.PCF8576BlinkOff
	fmt.Println("ok")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
