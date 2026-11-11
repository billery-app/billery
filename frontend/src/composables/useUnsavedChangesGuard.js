import { ref, computed, watch } from 'vue'

export function useUnsavedChangesGuard(getForm) {
  const originalJson = ref(null)
  const currentJson = ref(null)
  const showConfirm = ref(false)
  let resolver = null

  function stringifyForm() {
    const form = typeof getForm === 'function' ? getForm() : null
    return form ? JSON.stringify(form) : null
  }

  watch(
    () => stringifyForm(),
    (json) => { currentJson.value = json },
    { immediate: true }
  )

  const isDirty = computed(() => {
    if (originalJson.value === null || currentJson.value === null) return false
    return originalJson.value !== currentJson.value
  })

  function snapshot() {
    originalJson.value = stringifyForm()
  }

  function markSaved() {
    snapshot()
  }

  function requestClose() {
    if (!isDirty.value) return Promise.resolve('discard')
    showConfirm.value = true
    return new Promise((resolve) => { resolver = resolve })
  }

  function resolve(action) {
    showConfirm.value = false
    if (resolver) {
      const r = resolver
      resolver = null
      r(action)
    }
  }

  return {
    isDirty,
    showConfirm,
    requestClose,
    snapshot,
    markSaved,
    resolve,
  }
}
