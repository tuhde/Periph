//go:build tinygo

// NeoPixelTransport is the TinyGo implementation of the NeoPixel
// write-only transport. It uses the tinygo-org/drivers/ws2812 package
// rather than hand-rolling the SPI bit-encoding — that driver
// already implements WS2812 timing per TinyGo-supported board
// (typically cycle-counted bit-banged GPIO, not necessarily the SPI
// trick). Timing is therefore board-native, not the SPI bit-encoding
// used by every other platform in this repo.
package transport

import (
	"machine"

	"tinygo.org/x/drivers/ws2812"
)

// NeoPixelTransport is a TinyGo ws2812-backed implementation of the
// NeoPixel write-only transport.
type NeoPixelTransport struct {
	dev ws2812.Device
}

// NewNeoPixelTransport binds a NeoPixel strip to the given GPIO pin
// (data-in). The pin is configured for output by the ws2812 driver.
func NewNeoPixelTransport(pin machine.Pin) *NeoPixelTransport {
	return &NeoPixelTransport{dev: ws2812.New(pin)}
}

// Close is a no-op: the ws2812 driver does not expose a Close method.
func (n *NeoPixelTransport) Close() error {
	return nil
}

// Write transmits the buffer to the LED strip using the ws2812
// driver's per-board timing.
func (n *NeoPixelTransport) Write(data []byte) error {
	_, err := n.dev.Write(data)
	return err
}

// Read is a no-op for NeoPixel: strips are write-only. Provided to
// satisfy the Transport interface.
func (n *NeoPixelTransport) Read(count int) ([]byte, error) {
	return nil, nil
}

// WriteRead is a no-op for NeoPixel: strips are write-only. Provided
// to satisfy the Transport interface.
func (n *NeoPixelTransport) WriteRead(data []byte, count int) ([]byte, error) {
	return nil, nil
}
