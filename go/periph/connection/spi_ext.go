package connection

import "errors"

// ErrSPIExtensionUnsupported is returned by an SPIExtensions method the
// connection cannot honour (e.g. IdleClocks on a Linux spidev connection that
// has no GPIO chip select).
var ErrSPIExtensionUnsupported = errors.New("connection: SPI extension unsupported")

// SPIExtensions are the optional SPI operations beyond the Connection
// contract (specs/transport_spi.md, "Optional Extensions"), required by SD
// cards in SPI mode. An SPI connection that implements them satisfies this
// interface; chips that do not need them never call them.
//
// Read/ReadInto/WriteRead clock 0xFF on MOSI during the read phase.
type SPIExtensions interface {
	Connection

	// IdleClocks clocks n bits of 0xFF with CS deasserted (rounded up to bytes).
	IdleClocks(n int) error
	// SetSpeed changes SCK to the highest supported frequency not above hz.
	SetSpeed(hz uint32) error
	// Select asserts CS and holds it low across following Write/Read/WriteRead calls.
	Select() error
	// Deselect releases CS asserted by Select.
	Deselect() error
	// ReadInto fills buf with len(buf) bytes read from the device.
	ReadInto(buf []byte) error
}
