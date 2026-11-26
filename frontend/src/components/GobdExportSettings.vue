<template>
  <section class="settings-section" data-testid="gobd-export">
    <div class="card export-panel">
      <p class="export-description">
        {{ $t('gobd.description') }}
      </p>
      <div class="export-row">
        <label>{{ $t('gobd.from') }} <input v-model="exportFrom" type="date" data-testid="export-from" /></label>
        <label>{{ $t('gobd.to') }} <input v-model="exportTo" type="date" data-testid="export-to" /></label>
        <label class="export-check">
          <input v-model="exportDocuments" type="checkbox" />
          {{ $t('gobd.withFiles') }}
        </label>
        <button
          class="btn btn-primary"
          :disabled="exporting || !exportFrom || !exportTo"
          data-testid="export-button"
          @click="runExport"
        >{{ exporting ? $t('gobd.exporting') : $t('gobd.download') }}</button>
      </div>
      <p v-if="exportError" class="export-error">{{ exportError }}</p>
    </div>
  </section>
</template>

<script>
import exportAPI, { isCompleteZip } from '../services/exportService.js'

/**
 * The GoBD export (Settings → Daten & Export): a period, whether the PDFs and e-invoices go into
 * the archive, and the download. The server streams the ZIP; an archive that broke off on the way
 * (no end-of-central-directory record) is not saved.
 */
export default {
  name: 'GobdExportSettings',
  data() {
    const today = new Date()
    return {
      exportFrom: `${today.getFullYear()}-01-01`,
      exportTo: today.toISOString().split('T')[0],
      exportDocuments: true,
      exporting: false,
      exportError: null
    }
  },
  methods: {
    /** Downloads the GoBD export ZIP for the chosen period. */
    async runExport() {
      if (this.exportFrom > this.exportTo) {
        this.exportError = this.$t('gobd.datesReversed')
        return
      }
      this.exporting = true
      this.exportError = null
      try {
        const response = await exportAPI.gobdExport(this.exportFrom, this.exportTo, this.exportDocuments)
        const zip = new Blob([response.data], { type: 'application/zip' })
        // The server streams the archive; one that broke off on the way is not the export.
        if (!(await isCompleteZip(zip))) {
          this.exportError = this.$t('gobd.incomplete')
          return
        }
        const url = window.URL.createObjectURL(zip)
        const a = document.createElement('a')
        a.href = url
        a.download = `GoBD-Export_${this.exportFrom}_${this.exportTo}.zip`
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
        window.URL.revokeObjectURL(url)
      } catch (err) {
        this.exportError = this.$t('gobd.failed', { reason: err.message })
      } finally {
        this.exporting = false
      }
    }
  }
}
</script>

<style scoped>
.export-description {
  margin: 0 0 12px;
  color: var(--color-text-muted);
  font-size: 0.95em;
}

.export-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 20px;
  align-items: center;
}

.export-row label {
  display: flex;
  gap: 6px;
  align-items: center;
}

.export-error {
  color: var(--color-danger);
  margin: 10px 0 0;
}

@media (max-width: 600px) {
  .export-panel { padding: var(--space-3); }
  .export-row { align-items: stretch; flex-direction: column; }
  .export-row label { justify-content: space-between; flex-wrap: wrap; }
  .export-row label input[type="date"] { min-width: 0; }
  .export-row .btn-primary { min-height: 42px; }
}
</style>
