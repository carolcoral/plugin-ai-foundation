import {
  buildOutputSpec,
  buildReasoningOptions,
  parseProviderOptionsJson,
  type OutputMode,
  type ReasoningEffort,
  type ReasoningMode,
} from '@/utils/model-test-workbench'
import { numberOrUndefined, parseStringMapJson } from '@/utils/model-test-workbench-request'
import { shallowRef, watch } from 'vue'

export function useLanguageGenerationSettings() {
  const systemPrompt = shallowRef('')
  const temperature = shallowRef(0.7)
  const topP = shallowRef(1)
  const maxTokens = shallowRef(1024)
  const seed = shallowRef<number | undefined>()
  const maxRetries = shallowRef<number | undefined>(2)
  const reasoningMode = shallowRef<ReasoningMode>('DEFAULT')
  const reasoningEffort = shallowRef<ReasoningEffort>('MEDIUM')
  const testToolEnabled = shallowRef(false)
  const testToolApprovalEnabled = shallowRef(false)
  const externalTestToolEnabled = shallowRef(false)
  const agentTestToolsEnabled = shallowRef(false)
  const toolCallRepairEnabled = shallowRef(false)
  const outputMode = shallowRef<OutputMode>('TEXT')
  const outputSchemaText = shallowRef(`{
  "type": "object",
  "properties": {
    "title": {
      "type": "string"
    },
    "summary": {
      "type": "string"
    }
  },
  "required": ["title", "summary"]
}`)
  const outputChoicesText = shallowRef('yes\nno')
  const outputError = shallowRef('')
  const providerOptionsText = shallowRef('{}')
  const providerOptionsError = shallowRef('')
  const chatHeadersText = shallowRef('{}')
  const chatHeadersError = shallowRef('')

  function buildParameters(
    providerOptions: Record<string, Record<string, unknown>> | undefined,
    headers: Record<string, string> | undefined,
    output: ReturnType<typeof buildOutputSpec>['value'],
  ) {
    return {
      systemPrompt: systemPrompt.value,
      temperature: numberOrUndefined(temperature.value),
      topP: numberOrUndefined(topP.value),
      maxOutputTokens: numberOrUndefined(maxTokens.value),
      seed: numberOrUndefined(seed.value),
      maxRetries: numberOrUndefined(maxRetries.value),
      reasoning: buildReasoningOptions({
        mode: reasoningMode.value,
        effort: reasoningEffort.value,
      }),
      providerOptions,
      headers,
      output,
    }
  }

  function buildValidatedParameters(): ReturnType<typeof buildParameters> | undefined {
    const providerOptions = parseProviderOptionsJson(providerOptionsText.value)
    providerOptionsError.value = providerOptions.error || ''
    if (providerOptions.error) return undefined

    const headers = parseStringMapJson(chatHeadersText.value)
    chatHeadersError.value = headers.error || ''
    if (headers.error) return undefined

    const outputSpec = buildOutputSpec({
      mode: outputMode.value,
      schemaText: outputSchemaText.value,
      choicesText: outputChoicesText.value,
    })
    outputError.value = outputSpec.error || ''
    if (outputSpec.error) return undefined

    return buildParameters(providerOptions.value, headers.value, outputSpec.value)
  }

  function streamOptions() {
    return {
      testToolEnabled: testToolEnabled.value,
      testToolApprovalEnabled: testToolApprovalEnabled.value,
      externalTestToolEnabled: externalTestToolEnabled.value,
      agentTestToolsEnabled: agentTestToolsEnabled.value,
      toolCallRepairEnabled: toolCallRepairEnabled.value,
    }
  }

  watch(testToolApprovalEnabled, (enabled) => {
    if (enabled && !testToolEnabled.value) testToolEnabled.value = true
  })
  watch(testToolEnabled, (enabled) => {
    if (!enabled) testToolApprovalEnabled.value = false
  })

  return {
    systemPrompt,
    temperature,
    topP,
    maxTokens,
    seed,
    maxRetries,
    reasoningMode,
    reasoningEffort,
    testToolEnabled,
    testToolApprovalEnabled,
    externalTestToolEnabled,
    agentTestToolsEnabled,
    toolCallRepairEnabled,
    outputMode,
    outputSchemaText,
    outputChoicesText,
    outputError,
    providerOptionsText,
    providerOptionsError,
    chatHeadersText,
    chatHeadersError,
    buildParameters,
    buildValidatedParameters,
    streamOptions,
  }
}
