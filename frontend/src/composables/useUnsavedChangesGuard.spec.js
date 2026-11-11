import { describe, it, expect } from 'vitest'
import { ref, nextTick } from 'vue'
import { useUnsavedChangesGuard } from './useUnsavedChangesGuard.js'

function makeFormRef() {
  return ref({
    name: 'Original',
    marginTopMm: 10,
    blocks: [{ id: 'b1', type: 'header', order: 0 }],
  })
}

describe('useUnsavedChangesGuard', () => {
  it('reports not dirty right after snapshot()', () => {
    const form = makeFormRef()
    const guard = useUnsavedChangesGuard(() => form.value)
    guard.snapshot()
    expect(guard.isDirty.value).toBe(false)
  })

  it('becomes dirty after the form is mutated', async () => {
    const form = makeFormRef()
    const guard = useUnsavedChangesGuard(() => form.value)
    guard.snapshot()

    form.value.name = 'Renamed'
    await nextTick()
    expect(guard.isDirty.value).toBe(true)
  })

  it('detects nested mutations inside the form', async () => {
    const form = makeFormRef()
    const guard = useUnsavedChangesGuard(() => form.value)
    guard.snapshot()

    form.value.blocks.push({ id: 'b2', type: 'footer', order: 1 })
    await nextTick()
    expect(guard.isDirty.value).toBe(true)
  })

  it('resolves requestClose() with "discard" immediately when not dirty', async () => {
    const form = makeFormRef()
    const guard = useUnsavedChangesGuard(() => form.value)
    guard.snapshot()

    await expect(guard.requestClose()).resolves.toBe('discard')
    expect(guard.showConfirm.value).toBe(false)
  })

  it('keeps the dialog open and returns a pending promise when dirty', async () => {
    const form = makeFormRef()
    const guard = useUnsavedChangesGuard(() => form.value)
    guard.snapshot()

    form.value.name = 'Changed'
    await nextTick()

    let resolved = null
    const pending = guard.requestClose().then((action) => { resolved = action })
    expect(guard.showConfirm.value).toBe(true)

    guard.resolve('save')
    await pending
    expect(resolved).toBe('save')
    expect(guard.showConfirm.value).toBe(false)
  })

  it('markSaved() clears the dirty state', async () => {
    const form = makeFormRef()
    const guard = useUnsavedChangesGuard(() => form.value)
    guard.snapshot()

    form.value.name = 'Changed'
    await nextTick()
    expect(guard.isDirty.value).toBe(true)

    guard.markSaved()
    expect(guard.isDirty.value).toBe(false)
  })

  it('resolve() is a safe no-op when no request is pending', () => {
    const form = makeFormRef()
    const guard = useUnsavedChangesGuard(() => form.value)
    expect(() => guard.resolve('cancel')).not.toThrow()
    expect(guard.showConfirm.value).toBe(false)
  })
})
