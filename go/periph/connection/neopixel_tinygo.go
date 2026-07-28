//go:build tinygo

// NeoPixelConnection is the TinyGo implementation of the NeoPixel
// write-only connection. It uses the tinygo-org/drivers/ws2812 package
// rather than hand-rolling the SPI bit-encoding — that driver
// already implements WS2812 timing per TinyGo-supported board
// (typically cycle-counted bit-banged GPIO, not necessarily the SPI
// trick). Timing is therefore board-native, not the SPI bit-encoding
// used by every other platform in this repo.
package connection

import (
	"machine"

	"tinygo.org/x/drivers/ws2812"
)

// NeoPixelConnection is a TinyGo ws2812-backed implementation of the
// NeoPixel write-only connection.
type NeoPixelConnection struct {
	connectionBase
	dev ws2812.Device
}

// NewNeoPixelConnection binds a NeoPixel strip to the given GPIO pin
// (data-in). The pin is configured for output by the ws2812 driver. enPin
// may be nil if the strip's EN line is not wired; there is no INT line
// for a write-only strip.
func NewNeoPixelConnection(pin machine.Pin, enPin OutputPin) *NeoPixelConnection {
	return &NeoPixelConnection{connectionBase: connectionBase{enPin: enPin}, dev: ws2812.New(pin)}
}

// Close is a no-op: the ws2812 driver does not expose a Close method.
func (n *NeoPixelConnection) Close() error {
	return nil
}

// Write transmits the buffer to the LED strip using the ws2812
// driver's per-board timing. Writes are dropped when disabled.
func (n *NeoPixelConnection) Write(data []byte) error {
	if !n.IsEnabled() {
		return nil
	}
	_, err := n.dev.Write(data)
	return err
}

// Read is a no-op for NeoPixel: strips are write-only. Provided to
// satisfy the Connection interface.
func (n *NeoPixelConnection) Read(count int) ([]byte, error) {
	return nil, nil
}

// WriteRead is a no-op for NeoPixel: strips are write-only. Provided
// to satisfy the Connection interface.
func (n *NeoPixelConnection) WriteRead(data []byte, count int) ([]byte, error) {
	return nil, nil
}
