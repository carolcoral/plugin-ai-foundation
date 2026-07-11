import { describe, expect, it } from '@rstest/core'
import { nextTick } from 'vue'
import { useLanguageGenerationSettings } from './use-language-generation-settings'

describe('useLanguageGenerationSettings', () => {
  it('builds the chat request parameters from the current controls', () => {
    const settings = useLanguageGenerationSettings()
    settings.systemPrompt.value = ' Be concise '
    settings.temperature.value = 0.2
    settings.seed.value = 42
    settings.providerOptionsText.value = '{"openai":{"parallelToolCalls":false}}'
    settings.chatHeadersText.value = '{"X-Trace":"trace-1"}'
    settings.outputMode.value = 'CHOICE'
    settings.outputChoicesText.value = 'yes\nno\n'

    expect(settings.buildValidatedParameters()).toEqual({
      systemPrompt: ' Be concise ',
      temperature: 0.2,
      topP: 1,
      maxOutputTokens: 1024,
      seed: 42,
      maxRetries: 2,
      reasoning: undefined,
      providerOptions: { openai: { parallelToolCalls: false } },
      headers: { 'X-Trace': 'trace-1' },
      output: { type: 'CHOICE', choices: ['yes', 'no'] },
    })
  })

  it('reports validation errors without creating a request', () => {
    const settings = useLanguageGenerationSettings()
    settings.providerOptionsText.value = '[]'

    expect(settings.buildValidatedParameters()).toBeUndefined()
    expect(settings.providerOptionsError.value).toBe('Provider Options 必须是 JSON 对象')
  })

  it('keeps tool approval dependent on the test tool switch', async () => {
    const settings = useLanguageGenerationSettings()
    settings.testToolApprovalEnabled.value = true
    await nextTick()
    expect(settings.testToolEnabled.value).toBe(true)

    settings.testToolEnabled.value = false
    await nextTick()
    expect(settings.testToolApprovalEnabled.value).toBe(false)
  })
})
