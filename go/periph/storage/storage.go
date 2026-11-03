// Package storage defines the common block-device contract for storage chips
// (SD, SD NAND, SPI NOR, SPI NAND) — see specs/feature_block_device.md.
//
// Only the Full chip drivers implement BlockDevice. Units are bytes and block
// indices; a block is the read/program unit exposed to a filesystem. Periph
// ships no filesystem: BlockDevice is the seam where FAT / LittleFS adapters
// attach.
package storage

import "errors"

// Errors returned by BlockDevice operations (compare with errors.Is).
var (
	// ErrNotReady: device absent or initialisation failed.
	ErrNotReady = errors.New("storage: device not ready")
	// ErrOutOfRange: block index/count beyond BlockCount(), or unaligned Erase().
	ErrOutOfRange = errors.New("storage: out of range")
	// ErrNotErased: program to an un-erased block while auto-erase is off.
	ErrNotErased = errors.New("storage: block not erased")
	// ErrWriteProtected: card WP / status-register block protection.
	ErrWriteProtected = errors.New("storage: write protected")
	// ErrBadBlock: target erase unit is marked bad (NAND).
	ErrBadBlock = errors.New("storage: bad block")
	// ErrECC: uncorrectable ECC error on read (NAND).
	ErrECC = errors.New("storage: uncorrectable ECC error")
	// ErrCRC: SD command/data CRC mismatch.
	ErrCRC = errors.New("storage: CRC mismatch")
	// ErrTimeout: busy never cleared.
	ErrTimeout = errors.New("storage: timeout")
)

// BlockInfo describes the geometry and capabilities of a BlockDevice.
type BlockInfo struct {
	BlockSize  int    // read/program unit in bytes
	BlockCount uint32 // number of addressable blocks
	EraseSize  int    // erase unit in bytes (a multiple of BlockSize)
	NeedsErase bool   // a block must be erased before it is programmed
	ReadOnly   bool   // write protected
	BadBlocks  bool   // device may contain bad erase units
	Name       string // short device name (e.g. SD product name)
}

// BlockDevice is implemented by the Full driver of every storage chip.
type BlockDevice interface {
	// BlockSize returns the logical block size in bytes (read/program unit).
	BlockSize() int
	// BlockCount returns the number of addressable logical blocks.
	BlockCount() uint32
	// EraseSize returns the erase unit in bytes; equals BlockSize() when !NeedsErase().
	EraseSize() int
	// NeedsErase reports whether blocks must be erased before they are programmed.
	NeedsErase() bool

	// Read reads len(buf)/BlockSize() consecutive blocks starting at block.
	Read(block uint32, buf []byte) error
	// Write programs len(data)/BlockSize() consecutive blocks starting at block.
	Write(block uint32, data []byte) error
	// Erase erases the erase units covering [block, block+count). No-op on SD.
	Erase(block, count uint32) error
	// Sync blocks until all pending program/erase work has finished.
	Sync() error
	// Info returns geometry and capabilities.
	Info() BlockInfo

	// IsBad (optional, Info().BadBlocks): whether erase unit unit is bad.
	IsBad(unit uint32) bool
	// MarkBad (optional): persist a bad-block mark for erase unit unit.
	MarkBad(unit uint32) error
}
