import { describe, it, expect } from 'vitest'
import { useHistory } from './useHistory.js'

function setup() {
  let t = 0
  const clock = { advance: (ms) => { t += ms } }
  const history = useHistory({ mergeWindowMs: 500, now: () => t, limit: 4 })
  return { history, clock }
}

describe('useHistory', () => {
  it('undoes and redoes recorded states', () => {
    const { history, clock } = setup()
    let state = { v: 0 }
    const apply = (s) => { state = s }
    history.reset({ v: 0 })
    clock.advance(1000); history.record({ v: 1 })
    clock.advance(1000); history.record({ v: 2 })

    expect(history.canUndo.value).toBe(true)
    history.undo(apply)
    expect(state).toEqual({ v: 1 })
    history.undo(apply)
    expect(state).toEqual({ v: 0 })
    expect(history.canUndo.value).toBe(false)
    history.redo(apply)
    expect(state).toEqual({ v: 1 })
    expect(history.canRedo.value).toBe(true)
  })

  it('merges quick successive changes (typing) into one step', () => {
    const { history, clock } = setup()
    let state
    history.reset({ text: '' })
    clock.advance(1000); history.record({ text: 'H' })
    clock.advance(100); history.record({ text: 'Ha' })
    clock.advance(100); history.record({ text: 'Hal' })
    history.undo((s) => { state = s })
    expect(state).toEqual({ text: '' })
  })

  it('drops the redo branch on a new change and ignores unchanged states', () => {
    const { history, clock } = setup()
    history.reset({ v: 0 })
    clock.advance(1000); history.record({ v: 1 })
    history.undo(() => {})
    clock.advance(1000); history.record({ v: 0 })
    expect(history.canRedo.value).toBe(true)
    clock.advance(1000); history.record({ v: 5 })
    expect(history.canRedo.value).toBe(false)
  })

  it('keeps at most `limit` states and does not record while restoring', () => {
    const { history, clock } = setup()
    history.reset({ v: 0 })
    for (let i = 1; i <= 6; i++) { clock.advance(1000); history.record({ v: i }) }
    let undos = 0
    while (history.undo((s) => history.record({ v: s.v + 100 }))) undos++
    expect(undos).toBe(3)
  })
})
