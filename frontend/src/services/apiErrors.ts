import { i18n, t } from '../i18n/index.js'

/**
 * The messages of the backend in the interface language (#15). An error answer carries
 * `{ message, code, params }`: `message` is the German text, `code` the key of the same message in
 * the message files (`errors.<code>`), `params` the values of its placeholders. A parameter can be
 * a message itself (`{ code, params }`, e.g. the reason inside a sentence) or a list of values or
 * messages, which is shown joined with ", ". Without a code, or with a code this version of the SPA
 * does not know, the German `message` is shown as before.
 *
 * The codes live in `backend/.../api/error/ErrorCode.java` and `SaasErrorCode.java`;
 * `locales/errorCodes.spec.ts` checks that both message files know every one of them.
 */

/** A nested message in the parameters. */
interface CodedValue {
  code: string
  params?: Record<string, unknown>
}

/** The body of an error answer of the API. */
export interface ApiErrorBody {
  message?: string
  code?: string
  params?: Record<string, unknown>
}

function isCoded(value: unknown): value is CodedValue {
  return typeof value === 'object' && value !== null && typeof (value as CodedValue).code === 'string'
}

/** Whether the message files have this code (German is the fallback, so it decides). */
function known(code: string): boolean {
  return i18n.global.te(`errors.${code}`, 'de')
}

/** A parameter as text: nested messages translated, lists joined. */
function paramText(value: unknown): string {
  if (Array.isArray(value)) return value.map(paramText).join(', ')
  if (isCoded(value)) return codedText(value) ?? ''
  return value == null ? '' : String(value)
}

/** The translated text of a coded message, or undefined when the code is unknown. */
function codedText({ code, params }: CodedValue): string | undefined {
  if (!known(code)) return undefined
  const values: Record<string, string> = {}
  for (const [name, value] of Object.entries(params ?? {})) values[name] = paramText(value)
  return t(`errors.${code}`, values)
}

/**
 * The text to show for an error body: the translated message when its code is known, otherwise the
 * German message the backend sent.
 * @param data - `error.response.data` (already parsed)
 */
export function errorText(data: unknown): string | undefined {
  if (typeof data !== 'object' || data === null) return undefined
  const body = data as ApiErrorBody
  if (typeof body.code === 'string') {
    const text = codedText({ code: body.code, params: body.params })
    if (text !== undefined) return text
  }
  return typeof body.message === 'string' ? body.message : undefined
}

/**
 * A reason the backend sends next to a success (e.g. `mailProblem` / `mailProblemCode` of an
 * invitation) in the interface language.
 * @param text - the German text
 * @param code - its code, if any
 */
export function reasonText(text: string | null | undefined, code: string | null | undefined): string | undefined {
  if (code && known(code)) return t(`errors.${code}`)
  return text ?? undefined
}
