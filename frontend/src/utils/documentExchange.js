/**
 * The template exchange format with word processors (DOCX / ODT, `POST /api/documents/export` and
 * `/import`). The backend only converts between the files and a list of sections
 * (`{ heading, paragraphs }`); which block and field a line belongs to is decided here, because the
 * block registry knows the fields and their German labels.
 *
 * A section is one block: the heading is its display name ("Kopfzeile", "Adresse – Absender",
 * "Hinweise" …; a second block of the same kind gets " (2)"), each line below is "Label: Wert" for
 * a text field, and further lines of a multi-line field follow on lines of their own. Variables
 * stay as `{{name}}`. Blocks without text fields (line items, totals) are left out: their content
 * comes from the document.
 *
 * Importing applies such a file to the blocks of a style preset. A line that names no field, and
 * every section whose heading is no block (an ordinary letter from Word), becomes free text, which
 * goes into the notes block ("Hinweise") before its `{{notizen}}`.
 */
import { BLOCK_TYPES, blockDisplayName, getBlockByType } from './blockRegistry.js'
import { clone, createBlock } from './blockModel.js'

/** Field kinds that hold text the exchange carries; images, selects and columns stay in Billery. */
const TEXT_KINDS = new Set(['text', 'multiline'])

/** The text fields of a block type, in the order of the registry. */
function textFields(type) {
  const blockType = getBlockByType(type)
  return blockType ? (blockType.fields || []).filter(field => TEXT_KINDS.has(field.kind)) : []
}

/** "Text:" and "text" both match the label "Text". */
function normalize(text) {
  return String(text ?? '').trim().replace(/:$/, '').trim().toLowerCase()
}

/** Section headings of the blocks, numbered when a kind occurs more than once. */
function headingsOf(blocks) {
  const seen = new Map()
  return blocks.map(block => {
    const name = blockDisplayName(block)
    const count = (seen.get(name) || 0) + 1
    seen.set(name, count)
    return count === 1 ? name : `${name} (${count})`
  })
}

/**
 * The sections of a template for the export.
 * @param {Object[]} blocks - the template's blocks in display order
 * @returns {{heading: string, paragraphs: string[]}[]}
 */
export function templateToSections(blocks) {
  const headings = headingsOf(blocks)
  const sections = []
  blocks.forEach((block, index) => {
    const paragraphs = []
    for (const field of textFields(block.type)) {
      const value = String(block.data?.[field.key] ?? '')
      if (!value.trim()) continue
      const lines = value.split(/\r?\n/)
      paragraphs.push(`${field.label}: ${lines[0]}`.trimEnd())
      for (const line of lines.slice(1)) {
        if (line.trim()) paragraphs.push(line)
      }
    }
    if (paragraphs.length) sections.push({ heading: headings[index], paragraphs })
  })
  return sections
}

/**
 * Applies imported sections to the blocks of a preset.
 * @param {Object[]} presetBlocks - the blocks the new template starts with (not changed)
 * @param {{heading: ?string, paragraphs: string[]}[]} sections - from `POST /api/documents/import`
 * @returns {{blocks: Object[], fields: number, freeText: number}} the new blocks, how many fields
 *   were filled and how many lines went into the notes as free text
 */
export function applySections(presetBlocks, sections) {
  const blocks = clone(presetBlocks)
  const free = []
  let fields = 0

  for (const section of sections || []) {
    const paragraphs = (section.paragraphs || []).filter(line => String(line).trim())
    const block = findBlock(blocks, section.heading)
    if (!block) {
      if (section.heading && String(section.heading).trim()) free.push(String(section.heading).trim())
      free.push(...paragraphs)
      continue
    }
    const known = textFields(block.type)
    const filled = new Set()
    let current = null
    for (const line of paragraphs) {
      const labelled = matchField(known, line)
      if (labelled) {
        current = labelled.field
        // The first line of a field replaces the preset's value; the file is the user's text.
        block.data[current.key] = labelled.value
        filled.add(current.key)
      } else if (current && current.kind === 'multiline') {
        block.data[current.key] = block.data[current.key] ? `${block.data[current.key]}\n${line}` : line
      } else {
        free.push(line)
      }
    }
    fields += filled.size
  }

  if (free.length) addFreeText(blocks, free)
  return { blocks, fields, freeText: free.length }
}

/** The block a heading names: "Hinweise", "hinweise:", "Hinweise (2)" for the second one. */
function findBlock(blocks, heading) {
  if (!heading) return null
  const match = /^(.*?)(?:\s*\((\d+)\))?$/.exec(String(heading).trim())
  const name = normalize(match[1])
  const occurrence = Number(match[2] || 1)
  let count = 0
  for (const block of blocks) {
    if (normalize(blockDisplayName(block)) === name || normalize(getBlockByType(block.type)?.name) === name) {
      count += 1
      if (count === occurrence) return block
    }
  }
  return createMissingBlock(blocks, name)
}

/** A block the file names but the preset lacks (an address block, notes …) is added before the footer. */
function createMissingBlock(blocks, name) {
  for (const type of BLOCK_TYPES) {
    if (type.type === 'address') {
      for (const role of ['sender', 'receiver']) {
        const block = createBlock('address', { role })
        if (normalize(blockDisplayName(block)) === name) return insertBeforeFooter(blocks, block)
      }
    } else if (normalize(type.name) === name && textFields(type.type).length) {
      return insertBeforeFooter(blocks, createBlock(type.type))
    }
  }
  return null
}

function insertBeforeFooter(blocks, block) {
  const footer = blocks.findIndex(b => b.type === 'footer')
  blocks.splice(footer === -1 ? blocks.length : footer, 0, block)
  return block
}

/** "Label: value" for one of the block's fields, or null. */
function matchField(fields, line) {
  const colon = line.indexOf(':')
  if (colon <= 0) return null
  const label = normalize(line.slice(0, colon))
  const field = fields.find(f => normalize(f.label) === label)
  return field ? { field, value: line.slice(colon + 1).trim() } : null
}

/**
 * Free text goes into the notes block before its `{{notizen}}` (the document's own notes keep
 * printing); a template without notes block gets one before the footer.
 */
function addFreeText(blocks, lines) {
  let notes = blocks.find(b => b.type === 'notes')
  if (!notes) notes = insertBeforeFooter(blocks, createBlock('notes'))
  const text = lines.join('\n')
  const existing = String(notes.data.content || '').trim()
  notes.data.content = existing ? `${text}\n\n${existing}` : text
}
