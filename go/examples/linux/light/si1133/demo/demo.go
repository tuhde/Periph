//go:build linux && !tinygo

// Si1133 demo example — Linux host.
//
// UV and daylight monitor: gains, IR photodiodes, autonomous mode with a threshold
// interrupt, burst mode and the parameter table.
package main

import (
	"fmt"
	"os"
	"strconv"
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
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	chk(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x55"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x55) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := light.NewSi1133Full(conn) // Create Si1133 driver, (connection) → (*Si1133Full, error)
	chk(err)
	chk(chip.Init()) // Check PART_ID, reset, UV + white channels, () → error

	// --- Identity ---
	// PART_ID 0x33 identifies the Si1133; HW_ID and REV_ID give silicon and
	// product revision. RESPONSE0 shows the sequencer state and command counter.
	part, hw, rev, err := chip.ReadID() // Identity registers, () → (partID, hwID, revID uint8, error)
	chk(err)
	resp, err := chip.ReadResponse() // RESPONSE0 decoded, () → (Si1133Response, error)
	chk(err)
	fmt.Printf("PART_ID 0x%02X  HW_ID 0x%02X  REV_ID 0x%02X  response %+v\n", part, hw, rev, resp)

	// --- UV and daylight monitor: 20 s at 1 Hz ---
	// The counts are raw ADC values; a UV index needs Silicon Labs' calibration
	// for the product's window, so watch the trend.
	for i := 0; i < 20; i++ {
		m, err := chip.Read() // Forced measurement, () → (Si1133Reading {UV counts, White counts}, error)
		chk(err)
		fmt.Printf("%2d s  UV %8d  white %8d\n", i, m.UV, m.White)
		time.Sleep(time.Second)
	}

	// --- Gain settings on a constant light level ---
	// HW_GAIN doubles the integration time per step, SW_GAIN accumulates 2^n
	// samples, HIGH_SIGNAL divides the sensitivity by 14.5 for direct sunlight.
	chk(chip.EnableChannels(0x04)) // CHAN_LIST, (mask) → error
	for _, g := range []struct {
		hw, sw uint8
		high   bool
	}{{2, 0, false}, {6, 0, false}, {6, 2, false}, {6, 0, true}} {
		chk(chip.ConfigureChannel(2, light.Si1133MuxWhite, g.hw, g.sw, 0, g.high, true, 0)) // Channel setup, (channel, adcmux, hwGain, swGain, decimRate, highSignal, bits24, postshift) → error
		v, err := chip.ReadChannels(true)                                                   // Forced read, (force) → ([]int32, error)
		chk(err)
		fmt.Printf("hw_gain %d sw_gain %d high_signal %v -> %d\n", g.hw, g.sw, g.high, v[0])
	}

	// --- IR photodiodes ---
	for _, ir := range []struct {
		name string
		mux  uint8
	}{{"small", light.Si1133MuxSmallIR}, {"medium", light.Si1133MuxMediumIR}, {"large", light.Si1133MuxLargeIR}} {
		x, err := chip.ReadIR(ir.mux) // IR photodiode once, (photodiode) → (int32 counts, error)
		chk(err)
		fmt.Printf("IR %-6s %d\n", ir.name, x)
	}
	uv, err := chip.ReadUV() // UV on the scratch channel, () → (int32 counts, error)
	chk(err)
	white, err := chip.ReadWhite() // White on the scratch channel, () → (int32 counts, error)
	chk(err)
	fmt.Printf("UV %d, white %d\n", uv, white)

	// --- Autonomous mode with a UV threshold ---
	// Channels 0 and 1 run every second; channel 0 only interrupts when crossing
	// THRESHOLD0, channel 1 on every result.
	chk(chip.ConfigureChannel(0, light.Si1133MuxUV, 9, 0, 0, false, true, 0)) // Channel setup, (channel, adcmux, ...) → error
	chk(chip.EnableChannels(0x03))                                            // CHAN_LIST, (mask) → error
	chk(chip.SetThreshold(0, 500))                                            // Threshold value, (index, value) → error
	chk(chip.SetChannelThreshold(0, 1))                                       // Threshold select, (channel, index) → error
	chk(chip.SetChannelCounter(1, 1))                                         // Counter for a channel, (channel, index) → error
	chk(chip.DisableInterrupt(light.Si1133SourceChannel1))                    // IRQ_ENABLE clear, (source) → error
	chk(chip.EnableInterrupt(light.Si1133SourceChannel0))                     // IRQ_ENABLE set, (source) → error
	chk(chip.OnInterrupt(func(status uint8) {                                 // Subscribe, (callback) → error
		v, _ := chip.ReadChannels(false)
		fmt.Printf("interrupt 0x%02X  values %v\n", status, v)
	}))
	chk(chip.StartAutonomous(0.1, [3]uint8{10, 10, 10})) // Autonomous mode, (rateS, counters) → error
	time.Sleep(5 * time.Second)
	chk(chip.OffInterrupt())                              // Unsubscribe, () → error
	chk(chip.Pause())                                     // PAUSE, () → error
	chk(chip.EnableInterrupt(light.Si1133SourceChannel1)) // IRQ_ENABLE set, (source) → error

	// --- Burst mode: four readings in a row ---
	chk(chip.SetBurst(4)) // Burst mode, (count) → error
	chk(chip.Force())     // FORCE without waiting, () → error
	time.Sleep(500 * time.Millisecond)
	mask, err := chip.DataReady() // IRQ_STATUS mask, () → (uint8, error)
	chk(err)
	v, err := chip.ReadChannels(false) // Decode current results, (force=false) → ([]int32, error)
	chk(err)
	fmt.Printf("burst ready 0x%02X values %v\n", mask, v)
	chk(chip.SetBurst(0))

	// --- Parameter table ---
	chk(chip.WriteParameter(0x1E, 7))      // PARAM_SET, (address, value) → error
	chan0, err := chip.ReadParameter(0x01) // PARAM_QUERY, (address) → (uint8, error)
	chk(err)
	rh, _ := chip.ReadParameter(0x1A)
	rl, _ := chip.ReadParameter(0x1B)
	c2, _ := chip.ReadParameter(0x1E)
	fmt.Printf("CHAN_LIST 0x%02X  MEASRATE %d  MEASCOUNT2 %d\n", chan0, uint16(rh)<<8|uint16(rl), c2)
	poll, err := chip.PollInterrupt() // IRQ_STATUS mask, () → (uint8, error)
	chk(err)
	fmt.Printf("poll 0x%02X\n", poll)

	// --- Finish ---
	// SetI2CAddress would move the chip to another address until the next
	// reset; the demo keeps 0x55 and only shows the call.
	// chk(chip.SetI2CAddress(0x56)) // Change address, (address) → error
	chk(chip.Reset())     // RESET_SW + defaults, () → error
	m, err := chip.Read() // Forced measurement, () → (Si1133Reading, error)
	chk(err)
	fmt.Printf("after reset UV %d white %d\n", m.UV, m.White)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
