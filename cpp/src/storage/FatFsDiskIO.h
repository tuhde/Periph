#pragma once
// FatFs (ChaN) low-level disk glue on top of BlockDevice.
//
// FatFs expects the application to provide disk_initialize / disk_status /
// disk_read / disk_write / disk_ioctl. This header defines them on top of any
// BlockDevice, so every Periph storage chip that implements BlockDevice can
// carry a FAT volume. Include it from exactly ONE translation unit of an
// application that links FatFs (it defines the functions, it is not a library
// source file), and call fatfs_attach() before f_mount().
//
// FatFs sectors are the device's logical blocks, so FatFs must be configured
// with _MIN_SS == _MAX_SS == block_size() (512 for SD). The application also
// provides get_fattime().

#include <stdint.h>
#include "BlockDevice.h"
#include "diskio.h"   // FatFs: DSTATUS, DRESULT, STA_*, CTRL_* (declares the functions with C linkage)

#ifndef FATFS_MAX_DRIVES
#define FATFS_MAX_DRIVES 1
#endif

static BlockDevice* fatfs_devices[FATFS_MAX_DRIVES];

/** @brief Bind physical drive @p pdrv to @p dev (call before f_mount). */
static inline void fatfs_attach(uint8_t pdrv, BlockDevice* dev) {
    if (pdrv < FATFS_MAX_DRIVES) fatfs_devices[pdrv] = dev;
}

static inline BlockDevice* fatfs_dev(uint8_t pdrv) {
    return pdrv < FATFS_MAX_DRIVES ? fatfs_devices[pdrv] : nullptr;
}

DSTATUS disk_status(BYTE pdrv) {
    BlockDevice* d = fatfs_dev(pdrv);
    if (!d || d->block_count() == 0) return STA_NOINIT | STA_NODISK;
    return d->info().read_only ? STA_PROTECT : 0;
}

DSTATUS disk_initialize(BYTE pdrv) {
    return disk_status(pdrv);   // the chip driver initialised the device in its constructor
}

DRESULT disk_read(BYTE pdrv, BYTE* buff, DWORD sector, UINT count) {
    BlockDevice* d = fatfs_dev(pdrv);
    if (!d) return RES_NOTRDY;
    BlockError e = d->read(sector, buff, count);
    return e == BlockError::Ok ? RES_OK : (e == BlockError::OutOfRange ? RES_PARERR : RES_ERROR);
}

DRESULT disk_write(BYTE pdrv, const BYTE* buff, DWORD sector, UINT count) {
    BlockDevice* d = fatfs_dev(pdrv);
    if (!d) return RES_NOTRDY;
    BlockError e = d->write(sector, buff, count);
    if (e == BlockError::WriteProtected) return RES_WRPRT;
    return e == BlockError::Ok ? RES_OK : (e == BlockError::OutOfRange ? RES_PARERR : RES_ERROR);
}

DRESULT disk_ioctl(BYTE pdrv, BYTE cmd, void* buff) {
    BlockDevice* d = fatfs_dev(pdrv);
    if (!d) return RES_NOTRDY;
    switch (cmd) {
        case CTRL_SYNC:
            return d->sync() == BlockError::Ok ? RES_OK : RES_ERROR;
        case GET_SECTOR_COUNT:
            *static_cast<DWORD*>(buff) = d->block_count();
            return RES_OK;
        case GET_SECTOR_SIZE:
            *static_cast<WORD*>(buff) = static_cast<WORD>(d->block_size());
            return RES_OK;
        case GET_BLOCK_SIZE:   // erase block size in sectors (f_mkfs aligns the data area to it)
            *static_cast<DWORD*>(buff) = d->erase_size() / d->block_size();
            return RES_OK;
        default:
            return RES_PARERR;
    }
}
