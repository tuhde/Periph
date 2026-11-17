//go:build tinygo

// HT16K33 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the HT16K33Full API on a Pico W.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x38, nil, nil) // Create I2C connection, (i2c, addr=0x38) → (*I2CConnection)
	lcd, err := display.NewHT16K33Full(conn)      // Create HT16K33 driver, (connection) → (*HT16K33Full, error)
	if err != nil {
		panic(err)
	}

	if err := lcd.Clear(); err != nil { // Blank the display, () → error
		panic(err)
	}
	if err := lcd.DeviceSelect(0); err != nil { // Select device on the bus, (subaddress 0–7) → error
		panic(err)
	}
	if err := lcd.SetMode(display.HT16K33Backplanes4, display.HT16K33Bias1_3); err != nil { // Set drive mode, (backplanes 1–4, bias 0/1) → error
		panic(err)
	}
	if err := lcd.SetBlink(display.HT16K33Blink2Hz, false); err != nil { // Set blink frequency, (frequency 0–3, alternate_bank=false) → error
		panic(err)
	}
	if err := lcd.SetBank(display.HT16K33Bank0, display.HT16K33Bank0); err != nil { // Select RAM bank, (input_bank 0/1, output_bank 0/1) → error
		panic(err)
	}
	digits := [4]uint8{5, 6, 7, 8}
	out := [4]uint8{
		display.HT16K33SevenSeg[digits[0]], // Encode 7-segment digit, (digit 0–9) → uint8
		display.HT16K33SevenSeg[digits[1]], // Encode 7-segment digit, (digit 0–9) → uint8
		display.HT16K33SevenSeg[digits[2]], // Encode 7-segment digit, (digit 0–9) → uint8
		display.HT16K33SevenSeg[digits[3]], // Encode 7-segment digit, (digit 0–9) → uint8
	}
	if err := lcd.WriteRaw(0, out[:]); err != nil { // Write raw bytes, (address 0–39, data) → error
		panic(err)
	}
	if err := lcd.Disable(); err != nil { // Disable display output, () → error
		panic(err)
	}
	if err := lcd.Enable(); err != nil { // Enable display output, () → error
		panic(err)
	}
	fmt.Println("ok")
}
