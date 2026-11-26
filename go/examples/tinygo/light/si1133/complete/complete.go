//go:build tinygo

// Si1133 complete example — TinyGo / Raspberry Pi Pico W.
//
// Calls every method of the Full driver once.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x55, nil, nil) // Create I2C connection, (i2c, addr=0x55) → (*I2CConnection)

	chip, err := light.NewSi1133Full(conn) // Create Si1133 driver, (connection) → (*Si1133Full, error)
	chk(err)
	// Constructor stores the connection only; no bus traffic yet.
	chk(chip.Init()) // Check PART_ID, reset, UV + white channels, () → error
	// Waits 25 ms, verifies PART_ID 0x33, RESET_SW, channel 0 = UV, channel 1 = white, 24-bit outputs.

	part, hw, rev, err := chip.ReadID() // Identity registers, () → (partID, hwID, revID uint8, error)
	chk(err)
	// PART_ID is 0x33; HW_ID bits 4:0 are 0x03 for the Si1133-AA00.
	fmt.Printf("PART_ID 0x%02X HW_ID 0x%02X REV_ID 0x%02X\n", part, hw, rev)
	resp, err := chip.ReadResponse() // RESPONSE0 decoded, () → (Si1133Response {State, Error, Counter}, error)
	chk(err)
	// State bits RUNNING/SUSPEND/SLEEP, the CMD_ERR flag and the 4-bit command counter.
	fmt.Printf("%+v\n", resp)

	m, err := chip.Read() // Forced measurement, () → (Si1133Reading {UV counts, White counts}, error)
	chk(err)
	// FORCE, wait for both channels in IRQ_STATUS, decode two signed 24-bit counts.
	fmt.Printf("UV %d white %d\n", m.UV, m.White)
	x, err := chip.ReadUV() // UV photodiode once, () → (int32 counts, error)
	chk(err)
	// Uses channel 5 as a scratch channel and restores CHAN_LIST afterwards.
	fmt.Println("read_uv", x)
	x, err = chip.ReadWhite() // White photodiode once, () → (int32 counts, error)
	chk(err)
	// Same scratch channel, HW_GAIN 7.
	fmt.Println("read_white", x)
	x, err = chip.ReadIR(light.Si1133MuxLargeIR) // IR photodiode once, (photodiode) → (int32 counts, error)
	chk(err)
	// Small, medium or large IR photodiode.
	fmt.Println("read_ir", x)

	chk(chip.ConfigureChannel(2, light.Si1133MuxLargeWhite, 4, 1, 0, false, false, 0)) // Channel setup, (channel, adcmux, hwGain, swGain, decimRate, highSignal, bits24, postshift) → error
	// Writes ADCCONFIG2, ADCSENS2 and ADCPOST2; this channel reports 16-bit unsigned counts.
	chk(chip.EnableChannels(0x07)) // CHAN_LIST, (mask) → error
	// Channels 0, 1 and 2 now run on every FORCE.
	v, err := chip.ReadChannels(true) // Forced read of all channels, (force=true) → ([]int32, error)
	chk(err)
	// Results in channel order: 24-bit signed for channels 0/1, 16-bit for channel 2.
	fmt.Println("channels", v)
	chk(chip.Force()) // FORCE without waiting, () → error
	// Starts a measurement; the results arrive in HOSTOUT when IRQ_STATUS flags the channels.
	time.Sleep(100 * time.Millisecond)
	mask, err := chip.DataReady() // IRQ_STATUS mask, () → (uint8, error)
	chk(err)
	// Bit c set when channel c finished; this read clears the flags.
	fmt.Printf("data ready 0x%02X\n", mask)
	v, err = chip.ReadChannels(false) // Decode current results, (force=false) → ([]int32, error)
	chk(err)
	// No new measurement, only HOSTOUT decoded.
	fmt.Println("channels", v)

	chk(chip.SetThreshold(0, 2000)) // Threshold value, (index, value) → error
	// THRESHOLD0 = 2000 counts (16-bit).
	chk(chip.SetChannelThreshold(0, 1)) // Threshold select, (channel, index) → error
	// Channel 0 interrupts only when crossing THRESHOLD0 (index 1); 0 turns it off.
	chk(chip.SetChannelThreshold(0, 0))
	chk(chip.SetChannelCounter(2, 2)) // Counter for a channel, (channel, index) → error
	// Channel 2 uses MEASCOUNT1 in autonomous mode; 0 keeps a channel forced-only.
	chk(chip.StartAutonomous(0.1, [3]uint8{10, 5, 1})) // Autonomous mode, (rateS, counters) → error
	// MEASRATE = 0.1 s; channels 0/1 run every 1 s (MEASCOUNT0 = 10), channel 2 every 0.5 s.
	time.Sleep(1500 * time.Millisecond)
	mask, err = chip.PollInterrupt() // IRQ_STATUS mask, () → (uint8, error)
	chk(err)
	// Same as DataReady; also releases the INT pin.
	fmt.Printf("poll 0x%02X\n", mask)
	chk(chip.Pause()) // PAUSE, () → error
	// Stops the autonomous measurements.
	chk(chip.SetBurst(4)) // Burst mode, (count) → error
	// Four readings per burst at MEASRATE; 0 disables burst mode.
	chk(chip.SetBurst(0))

	chk(chip.WriteParameter(0x1E, 3)) // PARAM_SET, (address, value) → error
	// Direct parameter-table access: MEASCOUNT2 = 3.
	p, err := chip.ReadParameter(0x1E) // PARAM_QUERY, (address) → (uint8, error)
	chk(err)
	// Reads it back through RESPONSE1.
	fmt.Println("MEASCOUNT2", p)

	chk(chip.DisableInterrupt(light.Si1133SourceChannel2)) // IRQ_ENABLE clear, (source) → error
	// Channel 2 no longer drives INT.
	chk(chip.EnableInterrupt(light.Si1133SourceChannel2)) // IRQ_ENABLE set, (source) → error
	// Channel 2 drives INT again.
	chk(chip.OnInterrupt(func(status uint8) { // Subscribe, (callback) → error
		fmt.Printf("interrupt 0x%02X\n", status)
	}))
	// Without an INT pin on the connection the driver polls IRQ_STATUS.
	_, err = chip.ReadChannels(true)
	chk(err)
	time.Sleep(100 * time.Millisecond)
	chk(chip.OffInterrupt()) // Unsubscribe, () → error
	// Stops delivery.

	chk(chip.Reset()) // RESET_SW + defaults, () → error
	// Parameter table back to power-up values, then channel 0 = UV, channel 1 = white again.
	// chk(chip.SetI2CAddress(0x56)) // Change address, (address) → error
	// Volatile; the chip answers on the new address at once, so a new connection is needed.
}
