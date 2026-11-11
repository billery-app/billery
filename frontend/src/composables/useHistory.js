import { ref, computed } from 'vue'

/**
 * Undo/redo for an editor whose whole state can be serialised.
 *
 * The editor calls {@link record} after every change; changes that arrive
 * within `mergeWindowMs` of each other (typing in a field) are merged into one
 * step. {@link undo} / {@link redo} return the state to restore; the caller
 * applies it and must not record that change again (`isRestoring`).
 *
 * @param {Object} [options]
 * @param {number} [options.limit=100] - Maximum number of steps kept
 * @param {number} [options.mergeWindowMs=600] - Changes closer than this become one step
 * @param {() => number} [options.now] - Clock (tests)
 */
export function useHistory({ limit = 100, mergeWindowMs = 600, now = () => Date.now() } = {}) {
  const stack = ref([])
  const index = ref(-1)
  let lastRecordAt = 0
  let restoring = false

  /**
   * Starts a fresh history with the given state as the only entry.
   * @param {*} state - JSON-serialisable state
   */
  function reset(state) {
    stack.value = [JSON.stringify(state)]
    index.value = 0
    lastRecordAt = 0
  }

  /**
   * Records a new state (ignored while restoring or when nothing changed).
   * @param {*} state
   */
  function record(state) {
    if (restoring) return
    const json = JSON.stringify(state)
    if (stack.value[index.value] === json) return
    const t = now()
    const merge = index.value > 0 && t - lastRecordAt < mergeWindowMs
    const kept = stack.value.slice(0, index.value + (merge ? 0 : 1))
    kept.push(json)
    while (kept.length > limit) kept.shift()
    stack.value = kept
    index.value = kept.length - 1
    lastRecordAt = t
  }

  /**
   * Restores a state through `apply` without recording it.
   * @param {number} target - Stack index
   * @param {(state: *) => void} apply
   * @returns {boolean}
   */
  function go(target, apply) {
    if (target < 0 || target >= stack.value.length) return false
    index.value = target
    lastRecordAt = 0
    restoring = true
    try {
      apply(JSON.parse(stack.value[target]))
    } finally {
      restoring = false
    }
    return true
  }

  return {
    canUndo: computed(() => index.value > 0),
    canRedo: computed(() => index.value < stack.value.length - 1),
    reset,
    record,
    /** @param {(state: *) => void} apply */
    undo: (apply) => go(index.value - 1, apply),
    /** @param {(state: *) => void} apply */
    redo: (apply) => go(index.value + 1, apply),
    /** Whether a restore is being applied right now. */
    isRestoring: () => restoring
  }
}
