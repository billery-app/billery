<template>
  <div class="templates-view">
    <header class="view-header">
      <h1>Dokumentvorlagen</h1>
      <div class="header-actions">
        <label class="toggle">
          <input type="checkbox" v-model="showDocumentSpecific" @change="loadTemplates" />
          <span>Dokument-spezifische Vorlagen anzeigen</span>
        </label>
        <button @click="showStartModal = true" class="btn btn-primary">
          + Neue Vorlage
        </button>
      </div>
    </header>

    <div class="templates-grid">
      <div
        v-for="template in templates"
        :key="template.id"
        class="template-card"
        :class="{ 'is-default': template.isDefault, 'is-document-specific': template.documentSpecific }"
      >
        <button type="button" class="thumb-button" :title="`${template.name} bearbeiten`" @click="editTemplate(template)">
          <TemplateThumbnail
            :blocks="template.blocks || []"
            :css-content="template.cssContent || ''"
            :document-type-id="template.documentTypeId || 1"
            :margins="template"
            :max-width="240"
          />
        </button>
        <div class="template-header">
          <h3>{{ template.name }}</h3>
          <span v-if="template.isDefault" class="default-badge">Standard</span>
          <span v-if="template.documentSpecific" class="document-specific-badge">Dokument-spezifisch</span>
          <span class="document-type">{{ getDocumentTypeName(template.documentTypeId) }}</span>
        </div>

        <div class="template-meta">
          <small>
            Ränder: {{ template.marginTopMm }}/{{ template.marginBottomMm }}/{{ template.marginLeftMm }}/{{ template.marginRightMm }} mm
          </small>
          <small v-if="template.updatedAt">
            Bearbeitet: {{ formatDate(template.updatedAt) }}
          </small>
        </div>

        <div class="template-actions">
          <button @click="editTemplate(template)" class="btn btn-small btn-primary">
            Bearbeiten
          </button>
          <button
            v-if="!template.isDefault"
            @click="setAsDefault(template.id)"
            class="btn btn-small btn-secondary"
          >
            Als Standard
          </button>
          <button
            type="button"
            class="btn btn-small btn-secondary"
            :disabled="exporting === template.id"
            :aria-label="`Vorlage ${template.name} als Word-Datei (DOCX) exportieren`"
            @click="exportTemplate(template, 'docx')"
          >
            DOCX
          </button>
          <button
            type="button"
            class="btn btn-small btn-secondary"
            :disabled="exporting === template.id"
            :aria-label="`Vorlage ${template.name} als ODT exportieren`"
            @click="exportTemplate(template, 'odt')"
          >
            ODT
          </button>
          <button
            v-if="!template.isDefault"
            @click="deleteTemplate(template.id)"
            class="btn btn-small btn-danger"
          >
            Löschen
          </button>
        </div>
      </div>
    </div>

    <div v-if="templates.length === 0 && !loading" class="empty-state">
      <p>Keine Vorlagen vorhanden.</p>
      <button @click="showStartModal = true" class="btn btn-primary">
        Erste Vorlage erstellen
      </button>
    </div>

    <div v-if="loading" class="loading">
      Lade Vorlagen...
    </div>

    <TemplateStartModal
      v-if="showStartModal"
      @choose="startNewTemplate"
      @cancel="showStartModal = false"
    />

    <!-- Editor Modal -->
    <div v-if="showCreateEditor || showEditEditor" class="editor-modal" @click.self="onBackdropClick">
      <div class="editor-container">
        <TemplateEditor
          ref="editorRef"
          :template="showEditEditor ? cleanEditingTemplate : newTemplateDraft"
          @save="handleSave"
          @cancel="closeEditor"
        />
      </div>
    </div>
  </div>
</template>

<script>
import { ref, computed, onMounted } from 'vue'
import TemplateEditor from '../components/TemplateEditor.vue'
import TemplateStartModal from '../components/template/TemplateStartModal.vue'
import TemplateThumbnail from '../components/template/TemplateThumbnail.vue'
import { sanitizeHtml } from '../utils/sanitize.js'
import templateAPI from '../services/templateService.js'
import documentAPI from '../services/documentService.js'
import { normalizeBlocks } from '../utils/blockModel.js'
import { templateToSections } from '../utils/documentExchange.js'
import { downloadBlob } from '../utils/fileValidation.js'

export default {
  name: 'TemplatesView',
  components: {
    TemplateEditor,
    TemplateStartModal,
    TemplateThumbnail
  },
  data() {
    return {
      templates: [],
      loading: false,
      documentTypes: {
        1: 'Rechnung',
        2: 'Angebot',
        3: 'Kalkulation',
        4: 'Teilrechnung'
      }
    }
  },
  setup() {
    const editingTemplate = ref(null)
    const showCreateEditor = ref(false)
    // "Neue Vorlage": the start gallery yields a preset (no id) that seeds the editor.
    const showStartModal = ref(false)
    const newTemplateDraft = ref(null)
    const showEditEditor = ref(false)
    const loading = ref(false)
    const templates = ref([])
    const showDocumentSpecific = ref(false)
    const editorRef = ref(null)
    const documentTypes = ref({
      1: 'Rechnung',
      2: 'Angebot',
      3: 'Kalkulation',
      4: 'Teilrechnung'
    })

    const cleanEditingTemplate = computed(() => {
      if (!editingTemplate.value) return null

      try {
        return JSON.parse(JSON.stringify(editingTemplate.value))
      } catch (e) {
        console.error('Error parsing editingTemplate:', e)
        return null
      }
    })

    const editTemplate = (template) => {
      editingTemplate.value = JSON.parse(JSON.stringify(template))
      showEditEditor.value = true
    }

    const closeEditor = () => {
      showCreateEditor.value = false
      showEditEditor.value = false
      editingTemplate.value = null
      newTemplateDraft.value = null
    }

    const startNewTemplate = (draft) => {
      newTemplateDraft.value = draft
      showStartModal.value = false
      showCreateEditor.value = true
    }

    const onBackdropClick = () => {
      const editor = editorRef.value
      if (editor && typeof editor.requestClose === 'function') {
        editor.requestClose()
      } else {
        closeEditor()
      }
    }

    const handleSave = async (templateData) => {
      try {
        if (editingTemplate.value) {
          await templateAPI.updateTemplate(editingTemplate.value.id, templateData)
        } else {
          await templateAPI.createTemplate(templateData)
        }
        closeEditor()
        await loadTemplates()
      } catch (error) {
        console.error('Failed to save template:', error)
        alert('Fehler beim Speichern der Vorlage')
      }
    }

    const loadTemplates = async () => {
      loading.value = true
      try {
        const response = await templateAPI.getTemplates({
          includeDocumentSpecific: showDocumentSpecific.value
        })
        templates.value = response.data
      } catch (error) {
        console.error('Failed to load templates:', error)
        alert('Fehler beim Laden der Vorlagen')
      } finally {
        loading.value = false
      }
    }

    const getDocumentTypeName = (id) => {
      return documentTypes.value[id] || 'Unbekannt'
    }

    const formatDate = (dateString) => {
      if (!dateString) return '-'
      const date = new Date(dateString)
      return date.toLocaleDateString('de-DE')
    }

    const setAsDefault = async (templateId) => {
      try {
        await templateAPI.setTemplateAsDefault(templateId)
        await loadTemplates()
      } catch (error) {
        console.error('Failed to set default template:', error)
        alert('Fehler beim Setzen der Standardvorlage')
      }
    }

    const deleteTemplate = async (templateId) => {
      if (!confirm('Möchten Sie diese Vorlage wirklich löschen?')) {
        return
      }
      try {
        await templateAPI.deleteTemplate(templateId)
        await loadTemplates()
      } catch (error) {
        console.error('Failed to delete template:', error)
        alert('Fehler beim Löschen der Vorlage')
      }
    }

    // Id of the template whose export is running (its buttons are disabled meanwhile).
    const exporting = ref(null)

    /**
     * Downloads a template as DOCX or ODT: one section per block with its texts
     * (utils/documentExchange.js), which the start dialog can read back.
     */
    const exportTemplate = async (template, format) => {
      const sections = templateToSections(normalizeBlocks(template.blocks))
      if (!sections.length) {
        alert('Diese Vorlage enthält keine Texte zum Exportieren.')
        return
      }
      exporting.value = template.id
      try {
        const response = await documentAPI.exportDocument({ name: template.name, format, sections })
        const fileName = String(template.name || 'Vorlage').replace(/[\\/:*?"<>|]+/g, ' ').trim() || 'Vorlage'
        downloadBlob(response.data, `${fileName}.${format}`)
      } catch (error) {
        console.error('Failed to export template:', error)
        alert('Die Vorlage konnte nicht exportiert werden.')
      } finally {
        exporting.value = null
      }
    }

    onMounted(loadTemplates)

    return {
      editingTemplate,
      showCreateEditor,
      showStartModal,
      newTemplateDraft,
      startNewTemplate,
      showEditEditor,
      loading,
      templates,
      showDocumentSpecific,
      editorRef,
      documentTypes,
      cleanEditingTemplate,
      editTemplate,
      closeEditor,
      onBackdropClick,
      handleSave,
      loadTemplates,
      getDocumentTypeName,
      formatDate,
      setAsDefault,
      deleteTemplate,
      exporting,
      exportTemplate
    }
  }
}
</script>

<style scoped>
.templates-view {
  padding: 20px;
  max-width: 1400px;
  margin: 0 auto;
}

.view-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.view-header h1 {
  margin: 0;
  font-size: 1.75rem;
  color: var(--color-text);
}

.templates-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 350px), 1fr));
  gap: 20px;
}

.template-card {
  background: var(--color-surface);
  border-radius: 8px;
  box-shadow: var(--shadow-sm);
  padding: 16px;
  display: flex;
  flex-direction: column;
  transition: box-shadow 0.2s;
}

.template-card:hover {
  box-shadow: var(--shadow-lg);
}

.template-card.is-default {
  border: 2px solid var(--color-primary);
}

.template-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.template-header h3 {
  margin: 0;
  font-size: 1.1rem;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.default-badge {
  background: var(--color-primary);
  color: var(--color-on-primary);
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 0.75rem;
}

.document-specific-badge {
  background: var(--color-warning);
  color: var(--color-on-primary);
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 0.75rem;
}

.template-card.is-document-specific {
  border-left: 4px solid var(--color-warning);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.9rem;
  color: var(--color-text-strong);
  cursor: pointer;
  user-select: none;
}

.toggle input[type="checkbox"] {
  cursor: pointer;
}

.document-type {
  background: var(--color-border);
  color: var(--color-text-strong);
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 0.75rem;
}

.template-meta {
  display: flex;
  justify-content: space-between;
  color: var(--color-text-muted);
  font-size: 0.75rem;
  margin-bottom: 12px;
}

.template-actions .btn {
  flex: 1;
}

.template-actions {
  display: flex;
  gap: 8px;
}

.empty-state {
  text-align: center;
  padding: 60px 20px;
  color: var(--color-text-muted);
}

.empty-state p {
  margin-bottom: 20px;
  font-size: 1.1rem;
}

.loading {
  text-align: center;
  padding: 40px;
  color: var(--color-text-muted);
}

.editor-modal {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: var(--color-backdrop);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
  padding: 20px;
}

.editor-container {
  width: 100%;
  max-width: 1600px;
  height: 94vh;
  background: var(--color-surface);
  border-radius: 8px;
  overflow: hidden;
}

.thumb-button {
  display: flex;
  justify-content: center;
  width: 100%;
  padding: 12px;
  margin-bottom: 10px;
  border: none;
  border-radius: 6px;
  background: var(--color-surface-muted);
  cursor: pointer;
}

.thumb-button:hover {
  background: var(--color-border);
}

@media (max-width: 768px) {
  .templates-grid {
    grid-template-columns: 1fr;
  }

  .templates-view { padding: 12px; }
  .view-header { align-items: stretch; flex-direction: column; gap: 12px; }
  .header-actions { align-items: stretch; flex-direction: column; gap: 12px; }
  .toggle { align-items: flex-start; }
  .template-meta { align-items: flex-start; flex-direction: column; gap: 4px; }
  .editor-modal { padding: 0; }
  .editor-container { height: 100vh; height: 100dvh; border-radius: 0; }

  .template-actions {
    flex-direction: column;
  }
  
  .editor-container {
    height: 95vh;
    max-width: 100%;
  }
}
</style>
