import { describe, it, expect } from 'vitest'
import { isCompleteZip } from './exportService.js'

/** The end-of-central-directory record with the given comment. */
function endRecord(comment = '') {
  const bytes = new Uint8Array(22 + comment.length)
  const view = new DataView(bytes.buffer)
  view.setUint32(0, 0x06054b50, true)
  view.setUint16(20, comment.length, true)
  bytes.set(new TextEncoder().encode(comment), 22)
  return bytes
}

describe('isCompleteZip', () => {
  it('accepts an archive that ends with its end record, with or without a comment', async () => {
    expect(await isCompleteZip(new Blob(['PK\x03\x04 entries', endRecord()]))).toBe(true)
    expect(await isCompleteZip(new Blob([endRecord('Billery')]))).toBe(true)
  })

  it('rejects an archive that broke off before its central directory', async () => {
    expect(await isCompleteZip(new Blob(['PK\x03\x04 belege.csv ...']))).toBe(false)
    expect(await isCompleteZip(new Blob([]))).toBe(false)
  })

  it('rejects an end record that is not the end of the file', async () => {
    expect(await isCompleteZip(new Blob([endRecord(), 'more data after it']))).toBe(false)
  })
})
