// Package ioexpander contains drivers for I/O expander chips over I²C
// (PCF8574, PCF8575, MCP23017, etc.) and SiPo (TPIC6B595). The package
// name drops the underscore present in the directory name to satisfy
// go vet / staticcheck.
package ioexpander

// SiPo is the subset of a connection.Connection a TPIC6B595 driver needs:
// write-only bus access plus the optional SRCLR/G hardware features.
// Implemented by *connection.SIPOConnection.
type SiPo interface {
	Write(data []byte) error
	Clear() error
	SetOutputEnable(en bool) error
	Close() error
}
